package com.grok2api.gateway

import android.app.Application

/**
 * 进程入口：只做一件事——挂上全局崩溃捕获。
 *
 * 为什么不放在 Activity/Service 里：崩溃可能发生在任何组件（保活服务、OAuth 回调页、
 * 后台线程池），挂在 Application 才能全覆盖；而且这里是唯一保证在**任何**组件之前执行的地方。
 */
class GrokApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DiagReporter.install(this)
    }
}