package com.aiapp.data
object Log {
    fun e(tag: String, message: String, t: Throwable? = null) = println("E/$tag: $message")
    fun w(tag: String, message: String) = println("W/$tag: $message")
    fun i(tag: String, message: String) = println("I/$tag: $message")
}
