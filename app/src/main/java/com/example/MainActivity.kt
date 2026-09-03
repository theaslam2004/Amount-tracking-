package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.ui.AddTransactionScreen
import com.example.ui.BudgetViewModel
import com.example.ui.BudgetViewModelFactory
import com.example.ui.DashboardScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeController

import com.example.ui.BudgetsScreen
import com.example.ui.SavingsGoalsScreen
import com.example.ui.AddBudgetScreen
import com.example.ui.AddSavingsGoalScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.data.Transaction

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 1)
        }
        
        val db = AppDatabase.getDatabase(applicationContext)
        
        val authManager = com.example.data.auth.AuthManager(applicationContext)
        val cloudSyncManager = com.example.data.sync.CloudSyncManager(applicationContext)
        val repository = AppRepository(
            db.transactionDao(),
            db.budgetDao(),
            db.savingsGoalDao(),
            authManager,
            cloudSyncManager
        )
        val themeController = ThemeController.create(applicationContext)

        setContent {
            MyApplicationTheme(themeController = themeController) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BudgetApp(repository)
                }
            }
        }
        
        // Also run it on creation if permission was already granted previously
        scanRecentSmsIfAllowed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            scanRecentSmsIfAllowed()
        }
    }

    private fun scanRecentSmsIfAllowed() {
        val prefs = getSharedPreferences("budget_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("has_scanned_initial_sms", false)) return
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
                val cursor = contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date"),
                    "date >= ?",
                    arrayOf(oneHourAgo.toString()),
                    "date ASC"
                )
                
                cursor?.use { c ->
                    val bodyIndex = c.getColumnIndex("body")
                    val addressIndex = c.getColumnIndex("address")
                    
                    while (c.moveToNext()) {
                        val body = c.getString(bodyIndex) ?: ""
                        val address = c.getString(addressIndex) ?: ""
                        
                        val regex = Regex("(?i)(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
                        val match = regex.find(body)
                        
                        val isDebit = body.contains("debited", ignoreCase = true) || body.contains("spent", ignoreCase = true)
                        val isCredit = body.contains("credited", ignoreCase = true) || body.contains("deposited", ignoreCase = true)

                        if (match != null && (isDebit || isCredit)) {
                            val amountString = match.groupValues[1].replace(",", "")
                            val amount = amountString.toDoubleOrNull()
                            
                            if (amount != null) {
                                val type = if (isDebit) "EXPENSE" else "INCOME"
                                val db = AppDatabase.getDatabase(applicationContext)
                                db.transactionDao().insertTransaction(
                                    Transaction(
                                        amount = amount,
                                        description = "Past 1hr: $address",
                                        category = "Auto-Sync",
                                        type = type
                                    )
                                )
                            }
                        }
                    }
                }
                prefs.edit().putBoolean("has_scanned_initial_sms", true).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun BudgetApp(repository: AppRepository) {
    val navController = rememberNavController()
    val viewModel: BudgetViewModel = viewModel(
        factory = BudgetViewModelFactory(repository)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in listOf("dashboard", "budgets", "savings")) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        selected = currentRoute == "dashboard",
                        onClick = { navController.navigate("dashboard") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.BarChart, contentDescription = "Budgets") },
                        label = { Text("Budgets") },
                        selected = currentRoute == "budgets",
                        onClick = { navController.navigate("budgets") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = "Savings") },
                        label = { Text("Savings") },
                        selected = currentRoute == "savings",
                        onClick = { navController.navigate("savings") { launchSingleTop = true } }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController, 
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    viewModel = viewModel,
                    onAddTransactionClick = { navController.navigate("add") }
                )
            }
            composable("budgets") {
                BudgetsScreen(
                    viewModel = viewModel,
                    onAddBudgetClick = { navController.navigate("add_budget") }
                )
            }
            composable("savings") {
                SavingsGoalsScreen(
                    viewModel = viewModel,
                    onAddGoalClick = { navController.navigate("add_goal") }
                )
            }
            composable("add") {
                AddTransactionScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("add_budget") {
                AddBudgetScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("add_goal") {
                AddSavingsGoalScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
