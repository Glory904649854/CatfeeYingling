package com.catfee.yingling.core

/** 会话状态。UI、前台通知、悬浮窗共用同一份。 */
enum class SessionStatus {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    ERROR,
}

fun SessionStatus.label(): String = when (this) {
    SessionStatus.IDLE -> "待机"
    SessionStatus.STARTING -> "启动中"
    SessionStatus.RUNNING -> "运行中"
    SessionStatus.PAUSED -> "已暂停"
    SessionStatus.ERROR -> "异常"
}
