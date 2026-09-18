package com.grok2api.gateway

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 注册驱动：照抄 com.taixu.grokreg 的 WebDriver + Js 架构。
 *
 * 关键两点（此前自研版踩坑卡的根因）：
 * 1. runJs 用 [JSONTokener] 解包 evaluateJavascript 的返回值，且**永不返回 null**——
 *    解析失败兜 {state:"raw"}，脚本返回 null 兜 {state:"null-result"}；
 * 2. 每个 JS 脚本用 [wrap] 包成 IIFE + try/catch，JS 运行时错误永不冒泡，
 *    兜成 {state:"js-error", error:...}。状态机只读 optString("state") 就不会空转。
 */
object RegistrationDriver {

    const val SIGNUP_URL = "https://accounts.x.ai/sign-up?redirect=cloud-console"

    // ------------------------------------------------------------- JS 基建

    /** 公共 helper：可见性/文本聚合/React 填值/邮箱候选/可点节点/turnstile。照抄 taixu。 */
    private const val HELPERS = """
function __isVisible(node) {
    if (!node) return false;
    var style = window.getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
    var rect = node.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
}
function __textOf(node) {
    return [
        node.innerText,
        node.textContent,
        node.getAttribute('aria-label'),
        node.getAttribute('title'),
        node.getAttribute('placeholder'),
        node.getAttribute('data-testid'),
        node.getAttribute('name'),
        node.getAttribute('id'),
        node.getAttribute('autocomplete')
    ].filter(Boolean).join(' ').replace(/\s+/g, ' ').trim();
}
function __setInputValue(input, value) {
    if (!input) return false;
    input.focus();
    input.click();
    var proto = (input instanceof HTMLTextAreaElement) ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    var nativeSetter = desc && desc.set;
    var tracker = input._valueTracker;
    if (tracker) tracker.setValue('');
    if (nativeSetter) nativeSetter.call(input, value); else input.value = value;
    input.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, data: value, inputType: 'insertText' }));
    input.dispatchEvent(new InputEvent('input', { bubbles: true, data: value, inputType: 'insertText' }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    return String(input.value || '').trim() === String(value || '').trim();
}
function __emailCandidates() {
    var direct = Array.prototype.slice.call(document.querySelectorAll('input[data-testid="email"], input[name="email"], input[type="email"], input[autocomplete="email"], input[placeholder*="mail" i], input[aria-label*="mail" i]'));
    var all = Array.prototype.slice.call(document.querySelectorAll('input, textarea'));
    for (var i = 0; i < all.length; i++) {
        var node = all[i];
        var type = (node.getAttribute('type') || '').toLowerCase();
        if (['hidden','submit','button','checkbox','radio','file','search'].indexOf(type) >= 0) continue;
        var meta = __textOf(node).toLowerCase();
        if (meta.indexOf('email') >= 0 || meta.indexOf('e-mail') >= 0 || meta.indexOf('mail') >= 0
            || meta.indexOf('邮箱') >= 0 || meta.indexOf('电子邮件') >= 0) {
            direct.push(node);
        }
    }
    var seen = [];
    for (var j = 0; j < direct.length; j++) if (seen.indexOf(direct[j]) < 0) seen.push(direct[j]);
    return seen;
}
function __clickableNodes() {
    return Array.prototype.slice.call(document.querySelectorAll('button[type="submit"], button, a, [role="button"], input[type="submit"]'))
        .filter(function (node) {
            return __isVisible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true';
        });
}
function __cfToken() {
    try {
        var el = document.querySelector('input[name="cf-turnstile-response"]');
        var byInput = String((el && el.value) || '').trim();
        if (byInput) return byInput;
        if (window.turnstile && typeof turnstile.getResponse === 'function') {
            return String(turnstile.getResponse() || '').trim();
        }
    } catch (e) {}
    return '';
}
function __cfPresent() {
    return !!document.querySelector('input[name="cf-turnstile-response"]')
        || !!document.querySelector('iframe[src*="turnstile"], div.cf-turnstile, [data-sitekey], script[src*="turnstile"]');
}
"""

    /** 把 body 包成 IIFE：helpers 只声明一次，body 放 try 里，异常兜成 {state:'js-error'}。 */
    private fun wrap(body: String): String =
        "(function(){\n$HELPERS\ntry{\n$body\n}catch(e){return JSON.stringify({state:'js-error',error:String(e&&e.message||e)});}})()"

    /** 把 Kotlin 值安全地注入 JS 字符串字面量（照抄 taixu 的 jsStr）。 */
    private fun jsStr(s: String): String = "\"" + s.map { c ->
        when (c) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            '<' -> "\\u003c"; '>' -> "\\u003e"; '&' -> "\\u0026"
            else -> if (c < ' ') "\\u${c.code.toString(16).padStart(4, '0')}" else c.toString()
        }
    }.joinToString("") + "\""

    // ------------------------------------------------------------- 各步骤 JS（照抄 taixu Js 常量）

    /** 打分定位「使用邮箱注册」入口按钮（排除登出/切换账号）。 */
    val CLICK_EMAIL_SIGNUP: String = wrap("""
function scoreEntry(node) {
    var compact = [node.innerText, node.textContent, node.getAttribute('aria-label'), node.getAttribute('title'), node.getAttribute('href')]
        .filter(Boolean).join(' ').replace(/\s+/g, '');
    var lower = compact.toLowerCase();
    if (compact.indexOf('退出登录') >= 0 || compact.indexOf('登出') >= 0 || compact.indexOf('切换账号') >= 0
        || lower.indexOf('sign-out') >= 0 || lower.indexOf('signout') >= 0 || lower.indexOf('logout') >= 0
        || lower.indexOf('log-out') >= 0 || lower.indexOf('sign out') >= 0) return 0;
    if (compact.indexOf('使用邮箱注册') >= 0) return 100;
    if (lower.indexOf('signupwithemail') >= 0) return 95;
    if (lower.indexOf('continuewithemail') >= 0) return 90;
    if (lower.indexOf('email') >= 0 && (lower.indexOf('sign') >= 0 || lower.indexOf('continue') >= 0 || lower.indexOf('use') >= 0 || lower.indexOf('with') >= 0)) return 80;
    if (lower === 'email' || lower.indexOf('邮箱') >= 0) return 70;
    return 0;
}
var candidates = Array.prototype.slice.call(document.querySelectorAll('button, a, [role="button"]'))
    .filter(function (node) { return __isVisible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true'; })
    .map(function (node) { return { node: node, score: scoreEntry(node), text: __textOf(node).slice(0, 80) }; })
    .filter(function (item) { return item.score > 0; })
    .sort(function (a, b) { return b.score - a.score; });
if (!candidates.length) {
    var visible = __clickableNodes().map(function (n) { return __textOf(n).slice(0, 60); }).filter(Boolean).slice(0, 10);
    return JSON.stringify({ state: 'not-found', url: location.href, buttons: visible });
}
candidates[0].node.click();
return JSON.stringify({ state: 'clicked', text: candidates[0].text, url: location.href });
""")

    /** 填邮箱（返回 state=filled / not-ready / fill-failed）。 */
    fun fillEmail(email: String): String = wrap("""
var email = ${jsStr(email)};
var input = __emailCandidates().filter(function (node) { return __isVisible(node) && !node.disabled && !node.readOnly; })[0] || null;
if (!input) {
    return JSON.stringify({ state: 'not-ready', url: location.href, title: document.title });
}
var ok = __setInputValue(input, email);
var inputType = (input.getAttribute('type') || '').toLowerCase();
var isValid = inputType !== 'email' || input.checkValidity();
if (!ok || !isValid) {
    return JSON.stringify({ state: 'fill-failed', value: input.value || '', valid: isValid, url: location.href });
}
input.blur();
return JSON.stringify({ state: 'filled', url: location.href });
""")

    /** 提交邮箱：点提交按钮，退 form.requestSubmit，再退 Enter。 */
    val SUBMIT_EMAIL: String = wrap("""
var input = __emailCandidates().filter(function (node) { return __isVisible(node) && !node.disabled && !node.readOnly; })[0] || null;
if (!input || !(input.value || '').trim()) return JSON.stringify({ state: 'no-input' });
var inputType = (input.getAttribute('type') || '').toLowerCase();
if (inputType === 'email' && !input.checkValidity()) return JSON.stringify({ state: 'invalid-email' });
var submitButton = __clickableNodes().filter(function (node) {
    var text = __textOf(node).replace(/\s+/g, '');
    var lower = text.toLowerCase();
    return text.indexOf('注册') >= 0 || text.indexOf('继续') >= 0 || text.indexOf('下一步') >= 0 || text.indexOf('确认') >= 0
        || lower.indexOf('signup') >= 0 || lower.indexOf('sign up') >= 0 || lower.indexOf('continue') >= 0
        || lower.indexOf('next') >= 0 || lower.indexOf('createaccount') >= 0 || lower.indexOf('submit') >= 0;
})[0];
if (submitButton) {
    submitButton.click();
    return JSON.stringify({ state: 'clicked', text: __textOf(submitButton).slice(0, 60) });
}
var form = input.closest('form');
if (form) {
    if (form.requestSubmit) form.requestSubmit();
    else form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    return JSON.stringify({ state: 'form-submit' });
}
input.focus();
input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true }));
input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', bubbles: true, cancelable: true }));
return JSON.stringify({ state: 'enter' });
""")

    /** 探测 OTP 输入形态（aggregate 单框 / boxes 分格）。 */
    fun probeOtp(code: String): String = wrap("""
var code = ${jsStr(code)};
if (!code) return JSON.stringify({ state: 'not-ready' });
var aggregate = Array.prototype.slice.call(document.querySelectorAll(
    'input[data-input-otp="true"], input[name="code"], input[autocomplete="one-time-code"], input[inputmode="numeric"], input[inputmode="text"]'
)).filter(function (node) {
    return __isVisible(node) && !node.disabled && !node.readOnly && Number(node.maxLength || 6) > 1;
})[0];
if (aggregate) return JSON.stringify({ state: 'aggregate' });
var otpBoxes = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function (node) {
    if (!__isVisible(node) || node.disabled || node.readOnly) return false;
    var maxLength = Number(node.maxLength || 0);
    var ac = String(node.autocomplete || '').toLowerCase();
    return maxLength === 1 || ac === 'one-time-code';
});
return JSON.stringify({ state: otpBoxes.length >= code.length ? 'boxes' : 'not-ready', boxes: otpBoxes.length });
""")

    /** 填 OTP（聚合框一次填，分格逐字符填）。 */
    fun fillOtp(code: String): String = wrap("""
var code = ${jsStr(code)};
if (!code) return JSON.stringify({ state: 'empty-code' });
var aggregate = Array.prototype.slice.call(document.querySelectorAll(
    'input[data-input-otp="true"], input[name="code"], input[autocomplete="one-time-code"], input[inputmode="numeric"], input[inputmode="text"]'
)).filter(function (node) {
    return __isVisible(node) && !node.disabled && !node.readOnly && Number(node.maxLength || 6) > 1;
})[0];
if (aggregate) {
    __setInputValue(aggregate, code);
    return JSON.stringify({ state: String(aggregate.value || '').replace(/\s+/g, '') ? 'filled-aggregate' : 'aggregate-failed' });
}
var otpBoxes = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function (node) {
    if (!__isVisible(node) || node.disabled || node.readOnly) return false;
    var maxLength = Number(node.maxLength || 0);
    var ac = String(node.autocomplete || '').toLowerCase();
    return maxLength === 1 || ac === 'one-time-code';
});
if (otpBoxes.length >= code.length) {
    for (var i = 0; i < code.length; i++) {
        var ch = code[i] || '';
        var box = otpBoxes[i];
        __setInputValue(box, ch);
        box.dispatchEvent(new KeyboardEvent('keydown', { bubbles: true, key: ch }));
        box.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: ch }));
    }
    var merged = otpBoxes.slice(0, code.length).map(function (x) { return String(x.value || '').trim(); }).join('');
    return JSON.stringify({ state: merged.length ? 'filled-boxes' : 'boxes-failed' });
}
return JSON.stringify({ state: 'not-ready' });
""")

    /** 提交验证码。 */
    val SUBMIT_OTP: String = wrap("""
var btn = Array.prototype.slice.call(document.querySelectorAll('button[type="submit"], button')).filter(function (node) {
    return __isVisible(node) && !node.disabled && node.getAttribute('aria-disabled') !== 'true';
}).filter(function (node) {
    var t = (node.innerText || node.textContent || '').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('确认邮箱') >= 0 || t.indexOf('继续') >= 0 || t.indexOf('下一步') >= 0
        || t.indexOf('confirm') >= 0 || t.indexOf('continue') >= 0 || t.indexOf('next') >= 0;
})[0];
if (!btn) return JSON.stringify({ state: 'no-button' });
btn.focus();
btn.click();
return JSON.stringify({ state: 'clicked' });
""")

    /** 重新发送验证码。 */
    val RESEND_CODE: String = wrap("""
var nodes = Array.prototype.slice.call(document.querySelectorAll('button, a, [role="button"]'));
var target = nodes.filter(function (node) {
    var t = (node.innerText || node.textContent || '').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('重新发送') >= 0 || t.indexOf('resend') >= 0 || t.indexOf('再次发送') >= 0;
})[0];
if (target && !target.disabled) { target.click(); return JSON.stringify({ state: 'clicked' }); }
return JSON.stringify({ state: 'not-found' });
""")

    /** 填资料（姓名+密码）+ 勾选 + 等 Turnstile，返回 ready-to-submit / wait-cloudflare / not-ready。 */
    fun fillProfile(given: String, family: String, password: String): String = wrap("""
var givenName = ${jsStr(given)};
var familyName = ${jsStr(family)};
var pwd = ${jsStr(password)};
function pickInput(selector) {
    return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(function (node) {
        return __isVisible(node) && !node.disabled && !node.readOnly;
    })[0] || null;
}
var givenInput = pickInput('input[data-testid="givenName"], input[name="givenName"], input[autocomplete="given-name"], input[aria-label*="名"]');
var familyInput = pickInput('input[data-testid="familyName"], input[name="familyName"], input[autocomplete="family-name"], input[aria-label*="姓"]');
var passwordInput = pickInput('input[data-testid="password"], input[name="password"], input[type="password"], input[autocomplete="new-password"]');
if (!givenInput || !familyInput || !passwordInput) return JSON.stringify({ state: 'not-ready', url: location.href });
var ok1 = __setInputValue(givenInput, givenName);
var ok2 = __setInputValue(familyInput, familyName);
var ok3 = __setInputValue(passwordInput, pwd);
givenInput.blur(); familyInput.blur(); passwordInput.blur();
if (!ok1 || !ok2 || !ok3) return JSON.stringify({ state: 'fill-failed' });
if (__cfPresent()) {
    var token = __cfToken();
    if (token.length < 80) return JSON.stringify({ state: 'wait-cloudflare', tokenLen: token.length });
}
var submitBtn = __clickableNodes().filter(function (node) {
    var t = (node.innerText || node.textContent || '').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('完成注册') >= 0 || t.indexOf('创建账户') >= 0 || t.indexOf('signup') >= 0 || t.indexOf('createaccount') >= 0;
})[0];
return JSON.stringify({ state: submitBtn ? 'ready-to-submit' : 'filled-no-submit' });
""")

    /** 最终提交（确认 Turnstile 已通过后点「完成注册」）。 */
    val SUBMIT_FINAL: String = wrap("""
var titleHit = Array.prototype.slice.call(document.querySelectorAll('h1,h2,div,span')).some(function (el) {
    var t = (el.textContent || '').replace(/\s+/g, '');
    var lower = t.toLowerCase();
    return t.indexOf('完成注册') >= 0 || lower.indexOf('completeyoursignup') >= 0 || lower.indexOf('completesignup') >= 0;
});
if (!titleHit) return JSON.stringify({ state: 'not-final-page' });
if (__cfPresent()) {
    var token = __cfToken();
    if (token.length < 80) return JSON.stringify({ state: 'final-page-wait-cf', tokenLen: token.length });
}
var buttons = __clickableNodes();
var submitBtn = buttons.filter(function (node) {
    var t = [node.innerText, node.textContent, node.getAttribute('value'), node.getAttribute('aria-label'), node.getAttribute('title')]
        .filter(Boolean).join(' ').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('完成注册') >= 0 || t.indexOf('创建账户') >= 0 || t.indexOf('signup') >= 0 || t.indexOf('createaccount') >= 0;
})[0];
if (!submitBtn) {
    var texts = buttons.map(function (n) { return __textOf(n).slice(0, 40); }).filter(Boolean).slice(0, 8);
    return JSON.stringify({ state: 'final-page-no-submit', buttons: texts });
}
submitBtn.focus();
submitBtn.click();
return JSON.stringify({ state: 'final-page-clicked-submit' });
""")

    /** 页面状态探测（hasEmail/hasOtp/hasProfile/url/token）。 */
    val PAGE_STATE: String = wrap("""
var token = __cfToken();
var hasProfile = !!document.querySelector('input[data-testid="givenName"], input[name="givenName"], input[autocomplete="given-name"]');
var hasEmail = __emailCandidates().filter(function (n) { return __isVisible(n); }).length > 0;
var hasOtp = Array.prototype.slice.call(document.querySelectorAll('input')).some(function (node) {
    if (!__isVisible(node)) return false;
    var ac = String(node.autocomplete || '').toLowerCase();
    return Number(node.maxLength || 0) === 1 || ac === 'one-time-code' || node.getAttribute('data-input-otp') === 'true' || node.getAttribute('name') === 'code';
});
return JSON.stringify({
    state: 'ok', url: location.href, title: document.title,
    cfPresent: __cfPresent(), tokenLen: token.length,
    hasEmail: hasEmail, hasOtp: hasOtp, hasProfile: hasProfile
});
""")

    /** Turnstile 轻推（DOM click，无真实鼠标）。 */
    val NUDGE_TURNSTILE: String = wrap("""
try { if (window.turnstile && typeof turnstile.render === 'function') { /* keep */ } } catch (e) {}
var nodes = Array.prototype.slice.call(document.querySelectorAll('div,span,iframe')).filter(function (n) {
    var txt = String((n.className || '') + ' ' + (n.id || '') + ' ' + (n.getAttribute && n.getAttribute('src') || '')).toLowerCase();
    return txt.indexOf('turnstile') >= 0;
});
if (nodes.length && typeof nodes[0].click === 'function') { nodes[0].click(); return JSON.stringify({ state: 'nudged' }); }
return JSON.stringify({ state: 'no-widget' });
""")

    /** 滚动到 Turnstile（解除 overflow 裁剪 + 高亮）。 */
    val SCROLL_TO_TURNSTILE: String = wrap("""
function pickWidget() {
    var direct = document.querySelector('div.cf-turnstile, [data-sitekey]');
    if (direct && __isVisible(direct)) return direct;
    var frames = Array.prototype.slice.call(document.querySelectorAll('iframe')).filter(function (f) {
        var src = String(f.getAttribute('src') || '').toLowerCase();
        return src.indexOf('turnstile') >= 0 || src.indexOf('challenges.cloudflare.com') >= 0;
    });
    for (var i = 0; i < frames.length; i++) if (__isVisible(frames[i])) return frames[i];
    var hidden = document.querySelector('input[name="cf-turnstile-response"]');
    if (hidden && hidden.parentElement) return hidden.parentElement;
    return frames[0] || direct || null;
}
var w = pickWidget();
if (!w) return JSON.stringify({ state: 'no-widget' });
try {
    w.scrollIntoView({ block: 'center', inline: 'center' });
} catch (e) {
    try { w.scrollIntoView(); } catch (e2) {}
}
try {
    var p = w.parentElement, depth = 0;
    while (p && depth < 4) {
        var st = window.getComputedStyle(p);
        if (st.overflow === 'hidden' || st.overflowY === 'hidden') p.style.overflow = 'visible';
        p = p.parentElement; depth++;
    }
} catch (e) {}
var rect = w.getBoundingClientRect();
return JSON.stringify({
    state: 'scrolled',
    tag: (w.tagName || '').toLowerCase(),
    x: Math.round(rect.left), y: Math.round(rect.top),
    w: Math.round(rect.width), h: Math.round(rect.height)
});
""")

    // ------------------------------------------------------------- OAuth 设备流（照抄 taixu OAuthFlow）

    /** 授权页 phase 探测。 */
    val OAUTH_STATE: String = wrap("""
var url = String(location.href || '');
var host = String(location.host || '').toLowerCase();
var path = String(location.pathname || '');
var text = String(document.body ? (document.body.innerText || '') : '').replace(/\s+/g, ' ').trim();
function hasVisible(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)).some(__isVisible); }
var hasEmail = __emailCandidates().some(__isVisible);
var hasPassword = hasVisible('input[type="password"]');
var hasUserCode = hasVisible('input[name="user_code"]') || /oauth2\/device/.test(url);
var isAuthHost = /(^|\.)accounts\.x\.ai$/.test(host) || /(^|\.)auth\.x\.ai$/.test(host);
var isLoopback = /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?(\/|$)/i.test(url);
var isSignInPath = /\/(sign-in|signin|login|log-in)(\/|$|\?)/i.test(path);
var phase = 'other';
var invalidAction = /invalid\s*action/i.test(text);
if (isLoopback) phase = 'callback';
else if (url.indexOf('/device/done') >= 0 || text.indexOf('设备已授权') >= 0 || text.indexOf('已获授权') >= 0) phase = 'device-done';
else if (invalidAction) phase = 'invalid-action';
else if (url.indexOf('/oauth2/consent') >= 0 || url.indexOf('/consent') >= 0) phase = 'consent';
else if (hasUserCode) phase = 'device';
else if (!isAuthHost && host && host.indexOf('x.ai') >= 0) phase = 'foreign';
else if (!isAuthHost && host) phase = 'foreign';
else if (path.indexOf('/sign-up') >= 0 && url.indexOf('/oauth2/') < 0) phase = 'signup';
else if (hasPassword) phase = 'password';
else if (hasEmail) phase = 'email';
else if (isSignInPath) phase = 'signin';
else if (url.indexOf('/device/done') >= 0 || text.indexOf('设备已授权') >= 0) phase = 'device-done';
else if (text.indexOf('正在重定向') >= 0 || text.indexOf('Redirecting') >= 0) phase = 'redirect';
var cfTok = String(__cfToken() || '');
return JSON.stringify({
    state: 'ok', phase: phase, url: url, host: host, title: document.title,
    cfPresent: __cfPresent(), tokenLen: cfTok.length,
    hasEmail: hasEmail, hasPassword: hasPassword, hasUserCode: hasUserCode,
    authHost: isAuthHost, ready: String(document.readyState || ''),
    text: text.slice(0, 200)
});
""")

    /** 填设备码 + 点继续。 */
    fun oauthUserCode(code: String): String = wrap("""
var code = ${jsStr(code)};
var input = document.querySelector('input[name="user_code"]');
if (!input) {
    // 分格 OTP 形态（移动端实测：user_code 拆成单字符格）
    var boxes = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function (n) {
        return __isVisible(n) && !n.disabled && (Number(n.maxLength || 0) === 1 || String(n.autocomplete || '').toLowerCase() === 'one-time-code');
    });
    if (boxes.length >= code.replace(/-/g, '').length) {
        var chars = code.replace(/-/g, '').split('');
        for (var i = 0; i < chars.length && i < boxes.length; i++) __setInputValue(boxes[i], chars[i]);
        var merged = boxes.slice(0, chars.length).map(function (x) { return String(x.value || '').trim(); }).join('');
        return JSON.stringify({ state: merged.length ? 'filled-boxes' : 'boxes-failed' });
    }
    return JSON.stringify({ state: 'not-ready' });
}
var current = String(input.value || '').replace(/-/g, '');
if (current !== code.replace(/-/g, '')) __setInputValue(input, code);
var btn = __clickableNodes().filter(function (n) {
    var t = (n.innerText || n.textContent || '').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('继续') >= 0 || t === 'continue' || t === 'next' || t.indexOf('提交') >= 0 || t === 'submit';
})[0] || document.querySelector('button[type="submit"]');
if (!btn) return JSON.stringify({ state: 'filled-no-submit' });
btn.click();
return JSON.stringify({ state: 'submitted' });
""")

    /** consent 页专用：跳过按钮点击（实测点了「允许」也回 Invalid action），直接 form 注入 4 字段提交。 */
    val OAUTH_ALLOW_FORM: String = wrap("""
var uc = new URLSearchParams(location.search).get('user_code') || '';
var forms = Array.prototype.slice.call(document.querySelectorAll('form')).filter(function (f) {
    var t = String(f.innerText || '').replace(/\s+/g, '').toLowerCase();
    if (t.indexOf('隐私偏好') >= 0 || t.indexOf('全部允许') >= 0 || t.indexOf('cookie') >= 0) return false;
    return true;
});
var form = forms.filter(function (f) { return f.querySelector('[name=action]'); })[0] || forms[0];
if (!form) return JSON.stringify({ state: 'no-form', url: location.href });
function ensureField(name, val) {
    var el = form.querySelector('[name="' + name + '"]');
    if (!el) {
        el = document.createElement('input');
        el.type = 'hidden';
        el.name = name;
        form.appendChild(el);
    }
    el.value = val;
}
ensureField('action', 'allow');
ensureField('user_code', uc);
ensureField('principal_type', 'User');
ensureField('principal_id', '');
form.submit();
return JSON.stringify({ state: 'form-submit-allow', uc: uc.length });
""")

    /** 点「允许/同意/授权」（taixu 三段式）——非 consent 场景用；consent 页实测按钮点击回 Invalid action。 */
    val OAUTH_ALLOW: String = wrap("""
var ALLOW_EXACT = ['允许', '允許', '同意', '授权', '授權', '接受', '确认', '確認', '继续', '繼續',
                   'allow', 'authorize', 'approve', 'accept', 'confirm', 'continue', 'yes'];
var DENY = ['拒绝', '拒絕', '取消', '不允许', '不允許', '返回', '否', 'deny', 'cancel', 'reject', 'decline', 'no', 'back'];
var COOKIE = ['全部允许', '全部允許', '允许全部', '允許全部', '隐私偏好', '隱私偏好', 'allowall', 'acceptall', 'cookie', 'managecookies'];
function field(node) {
    return [node.innerText, node.textContent, node.getAttribute('value'), node.getAttribute('aria-label'),
            node.getAttribute('title'), node.getAttribute('data-testid'), node.getAttribute('name')]
        .filter(Boolean).map(function (s) { return String(s).replace(/\s+/g, '').toLowerCase(); })
        .filter(function (s) { return s.length > 0; });
}
function hit(list, words) {
    for (var i = 0; i < list.length; i++) for (var j = 0; j < words.length; j++)
        if (list[i].indexOf(words[j]) >= 0) return true;
    return false;
}
var nodes = __clickableNodes().filter(function (n) { var f = field(n); return !hit(f, COOKIE) && !hit(f, DENY); });
for (var i = 0; i < nodes.length; i++) {
    if (hit(field(nodes[i]), ALLOW)) { nodes[i].focus(); nodes[i].click(); return JSON.stringify({ state: 'clicked' }); }
}
var byValue = Array.prototype.slice.call(document.querySelectorAll('button[value="allow"], input[value="allow"], button[formaction*="allow"], button[data-action="allow"]')).filter(__isVisible)[0];
if (byValue) { byValue.click(); return JSON.stringify({ state: 'clicked-value' }); }
return JSON.stringify({ state: 'no-allow-button' });
""")


    /**
     * 邮箱密码登录（补登用）：当 sso cookie 已失效、授权页退化成账号密码表单时接手。
     *
     * 设备流授权页的推进依赖"已经登录"这一前提；补登的号可能是几小时前注册的，
     * sso 早过期了，此时页面停在 sign-in —— 必须用注册时落盘的邮箱密码重新登进去，
     * 否则授权页永远推不动（taixu 的 LoginFlow 就是为这件事存在的）。
     *
     * 返回 state：entry-clicked（先点开邮箱登录入口）/ email-submitted / email-form-submit /
     * email-filled-no-submit / login-submitted / password-filled-no-submit / wait-cloudflare /
     * not-ready。
     */
    fun oauthSignIn(email: String, password: String): String = wrap("""
var email = ${jsStr(email)};
var pwd = ${jsStr(password)};
function pick(sel) {
    return Array.prototype.slice.call(document.querySelectorAll(sel)).filter(function (n) {
        return __isVisible(n) && !n.disabled && !n.readOnly;
    })[0] || null;
}
var emailInput = __emailCandidates().filter(function (n) { return __isVisible(n) && !n.disabled && !n.readOnly; })[0] || null;
var pwdInput = pick('input[type="password"], input[name="password"], input[autocomplete="current-password"]');
if (!emailInput && !pwdInput) {
    // 首屏只有 SSO 按钮：先点「使用邮箱登录」把邮箱表单唤出来
    var entry = __clickableNodes().filter(function (n) {
        var compact = [n.innerText, n.textContent, n.getAttribute('data-testid'), n.getAttribute('aria-label')]
            .filter(Boolean).join(' ').replace(/\s+/g, '').toLowerCase();
        if (compact.indexOf('signout') >= 0 || compact.indexOf('logout') >= 0 || compact.indexOf('退出') >= 0) return false;
        return compact.indexOf('continue-with-email') >= 0 || compact.indexOf('continuewithemail') >= 0
            || compact.indexOf('使用邮箱登录') >= 0 || compact.indexOf('使用邮件登录') >= 0
            || compact.indexOf('使用邮箱注册') >= 0 || compact.indexOf('signinwithemail') >= 0
            || compact.indexOf('emailsignin') >= 0
            || (compact.indexOf('email') >= 0 && (compact.indexOf('sign') >= 0 || compact.indexOf('continue') >= 0));
    })[0];
    if (entry) {
        entry.click();
        return JSON.stringify({ state: 'entry-clicked', url: location.href });
    }
    return JSON.stringify({ state: 'not-ready', url: location.href });
}
if (emailInput) __setInputValue(emailInput, email);
if (!pwdInput) {
    var next = __clickableNodes().filter(function (n) {
        var t = (n.innerText || n.textContent || '').replace(/\s+/g, '').toLowerCase();
        return t.indexOf('下一步') >= 0 || t.indexOf('继续') >= 0 || t === 'next' || t === 'continue' || t.indexOf('登录') >= 0 || t === 'signin';
    })[0];
    if (next) { next.click(); return JSON.stringify({ state: 'email-submitted' }); }
    var f1 = emailInput.form;
    if (f1) { f1.submit(); return JSON.stringify({ state: 'email-form-submit' }); }
    return JSON.stringify({ state: 'email-filled-no-submit' });
}
__setInputValue(pwdInput, pwd);
if (__cfPresent()) {
    var cfTok = String(__cfToken() || '');
    if (cfTok.length < 80) return JSON.stringify({ state: 'wait-cloudflare', tokenLen: cfTok.length });
}
var loginBtn = __clickableNodes().filter(function (n) {
    var t = (n.innerText || n.textContent || '').replace(/\s+/g, '').toLowerCase();
    return t.indexOf('登录') >= 0 || t === 'signin' || t === 'login' || t.indexOf('log in') >= 0;
})[0] || pick('button[type="submit"], button[data-testid="sign-in-submit"]');
if (!loginBtn) return JSON.stringify({ state: 'password-filled-no-submit' });
loginBtn.focus();
loginBtn.click();
return JSON.stringify({ state: 'login-submitted' });
""")

    // ------------------------------------------------------------- 执行层（照抄 WebDriver.runJs）

    class DriverError(message: String) : Exception(message)

    /** 在 WebView 主线程执行 JS，JSONTokener 解包，永不返回 null。超时抛 DriverError。 */
    fun runJs(web: WebView, script: String, timeoutMs: Long = 15000): JSONObject {
        val latch = CountDownLatch(1)
        var raw: String? = null
        Handler(Looper.getMainLooper()).post {
            try {
                web.evaluateJavascript(script) { r -> raw = r; latch.countDown() }
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw DriverError("JS 执行超时")
        return parseJsResult(raw ?: "")
    }

    /** state 快捷取值。 */
    fun state(web: WebView, script: String, timeoutMs: Long = 15000): String =
        runJs(web, script, timeoutMs).optString("state", "")

    /** 从 CookieManager 抽 SSO。 */
    fun extractSso(web: WebView): Pair<String, String> {
        val raw = CookieManager.getInstance().getCookie("https://accounts.x.ai").orEmpty()
        var sso = ""; var ssoRw = ""
        for (part in raw.split(";")) {
            val kv = part.trim()
            when {
                kv.startsWith("sso=") && !kv.startsWith("sso-rw=") -> sso = kv.removePrefix("sso=")
                kv.startsWith("sso-rw=") -> ssoRw = kv.removePrefix("sso-rw=")
            }
        }
        return sso.ifBlank { ssoRw } to ssoRw
    }

    private fun parseJsResult(value: String): JSONObject {
        val trimmed = value.trim()
        if (trimmed == "null" || trimmed.isEmpty()) {
            return JSONObject().put("state", "null-result")
        }
        if (trimmed.startsWith("\"")) {
            try {
                val next = JSONTokener(trimmed).nextValue()
                if (next is String) {
                    return try { JSONObject(next) }
                    catch (e: Exception) { JSONObject().put("state", "raw").put("raw", next) }
                }
            } catch (e: Exception) { }
        }
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            JSONObject().put("state", "raw").put("raw", trimmed)
        }
    }
}