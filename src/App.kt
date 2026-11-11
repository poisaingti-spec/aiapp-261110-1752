package com.aiapp.llm
enum class Role { USER, ASSISTANT }
data class ChatMessage(val role: Role, val text: String)
