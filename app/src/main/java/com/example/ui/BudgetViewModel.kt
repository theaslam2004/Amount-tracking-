package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppRepository
import com.example.data.Budget
import com.example.data.SavingsGoal
import com.example.data.Transaction
import com.example.data.auth.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class CategoryInsight(
    val category: String,
    val thisMonthAmount: Double,
    val lastMonthAmount: Double,
    val suggestion: String
)

class BudgetViewModel(private val repository: AppRepository) : ViewModel() {
    val transactions: StateFlow<List<Transaction>> = repository.allTransactions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val budgets: StateFlow<List<Budget>> = repository.allBudgets
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val savingsGoals: StateFlow<List<SavingsGoal>> = repository.allSavingsGoals
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val userProfile: StateFlow<UserProfile> = repository.userProfile
    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val lastSyncedTime: StateFlow<Long> = repository.lastSyncedTime
    val syncStatus: StateFlow<String> = repository.syncStatus

    val monthlyInsights: StateFlow<List<CategoryInsight>> = transactions.map { txList ->
        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)
        
        cal.add(Calendar.MONTH, -1)
        val lastMonth = cal.get(Calendar.MONTH)
        val lastMonthYear = cal.get(Calendar.YEAR)
        
        fun isSameMonthYear(timestamp: Long, month: Int, year: Int): Boolean {
            val c = Calendar.getInstance().apply { timeInMillis = timestamp }
            return c.get(Calendar.MONTH) == month && c.get(Calendar.YEAR) == year
        }

        val thisMonthExpenses = txList.filter { 
            it.type == "EXPENSE" &&
            isSameMonthYear(it.timestamp, currentMonth, currentYear)
        }
        val lastMonthExpenses = txList.filter { 
            it.type == "EXPENSE" &&
            isSameMonthYear(it.timestamp, lastMonth, lastMonthYear)
        }
        
        val thisMonthGrouped = thisMonthExpenses.groupBy { it.category }.mapValues { it.value.sumOf { tx -> tx.amount } }
        val lastMonthGrouped = lastMonthExpenses.groupBy { it.category }.mapValues { it.value.sumOf { tx -> tx.amount } }
        
        val insights = mutableListOf<CategoryInsight>()
        for ((category, thisAmount) in thisMonthGrouped) {
            val lastAmount = lastMonthGrouped[category] ?: 0.0
            val suggestion = if (thisAmount > lastAmount && lastAmount > 0) {
                "Spending is high! You spent ${String.format("%.0f", lastAmount)} last month."
            } else if (thisAmount > lastAmount) {
                "New high spending in this category."
            } else {
                "Good job keeping it lower than last month's ${String.format("%.0f", lastAmount)}."
            }
            insights.add(CategoryInsight(category, thisAmount, lastAmount, suggestion))
        }
        insights
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        // Initial silent restore / sync check
        viewModelScope.launch {
            repository.restoreFromCloud()
        }
    }

    fun addTransaction(amount: Double, description: String, category: String, type: String = "EXPENSE") {
        viewModelScope.launch {
            repository.insertTransaction(Transaction(amount = amount, description = description, category = category, type = type))
            // Trigger automatic sync on new transaction
            repository.syncNow()
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            repository.syncNow()
        }
    }

    fun addBudget(category: String, limitAmount: Double) {
        viewModelScope.launch {
            repository.insertBudget(Budget(category = category, limitAmount = limitAmount))
            repository.syncNow()
        }
    }

    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            repository.deleteBudget(budget)
            repository.syncNow()
        }
    }

    fun addSavingsGoal(name: String, targetAmount: Double) {
        viewModelScope.launch {
            repository.insertSavingsGoal(SavingsGoal(name = name, targetAmount = targetAmount))
            repository.syncNow()
        }
    }

    fun updateSavingsGoalProgress(goal: SavingsGoal, addedAmount: Double) {
        viewModelScope.launch {
            repository.updateSavingsGoal(goal.copy(currentAmount = goal.currentAmount + addedAmount))
            // Also log it as an expense to deduct from total balance
            repository.insertTransaction(
                Transaction(
                    amount = addedAmount,
                    description = "Contribution to ${goal.name}",
                    category = "Savings",
                    type = "EXPENSE"
                )
            )
            repository.syncNow()
        }
    }

    fun deleteSavingsGoal(goal: SavingsGoal) {
        viewModelScope.launch {
            repository.deleteSavingsGoal(goal)
            repository.syncNow()
        }
    }

    fun syncNow(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val result = repository.syncNow()
            onComplete?.invoke(result.isSuccess)
        }
    }

    fun restoreFromCloud(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            repository.restoreFromCloud()
            onComplete?.invoke()
        }
    }

    fun signInWithGoogle(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val res = repository.signInWithGoogle()
            onComplete?.invoke(res.isSuccess)
        }
    }

    fun updateProfile(name: String, email: String, photoUrl: String?) {
        repository.updateProfile(name, email, photoUrl)
    }

    fun signOut() {
        repository.signOut()
    }
}
