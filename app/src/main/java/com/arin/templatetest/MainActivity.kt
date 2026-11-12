package com.arin.templatetest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class Item(val id: Int, val name: String)

class CounterViewModel : ViewModel() {
    private val _items = MutableStateFlow(listOf<Item>())
    val items = _items.asStateFlow()
    fun add() = viewModelScope.launch { _items.value = _items.value + Item(_items.value.size, "Item ${_items.value.size}") }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") { Home(onOpen = { nav.navigate("json") }) }
                    composable("json") { Text(Json.encodeToString(Item(1, "x")), Modifier.padding(32.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(onOpen: () -> Unit, vm: CounterViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Template test") }) },
        floatingActionButton = { FloatingActionButton(onClick = { vm.add() }) { Icon(Icons.Filled.Add, null) } },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Button(onClick = onOpen) { Text("Open") }
            items.forEach { Text(it.name) }
        }
    }
}
