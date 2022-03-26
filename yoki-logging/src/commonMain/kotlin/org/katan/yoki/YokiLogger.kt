package org.katan.yoki

public enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

public interface YokiLogger {

    public val defaultLevel: LogLevel

    public fun log(level: LogLevel, parameters: Array<Any>)
}
