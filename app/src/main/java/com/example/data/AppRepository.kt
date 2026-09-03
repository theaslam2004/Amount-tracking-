package com.example.data

import com.example.data.auth.AuthManager
import com.example.data.auth.UserProfile
import com.example.data.sync.CloudDataBackup
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class AppRepository(
    private val transactionDao: TransactionDao,
    private val budgetDao: BudgetDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val authManager: AuthManager,
    private val cloudSyncManager: CloudSyncManager
) {
    val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()
    val allBudgets: Flow<List<Budget>> = budgetDao.getAllBudgets()
    val allSavingsGoals: Flow<List<SavingsGoal>> = savingsGoalDao.getAllSavingsGoals()

    val userProfile: StateFlow<UserProfile> = authManager.userProfile
    val isSyncing: StateFlow<Boolean> = cloudSyncManager.isSyncing
    val lastSyncedTime: StateFlow<Long> = cloudSyncManager.lastSyncedTime
    val syncStatus: StateFlow<String> = cloudSyncManager.syncStatus

    suspend fun insertTransaction(transaction: Transaction) {
        transactionDao.insertTransaction(transaction)
    }

    suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.deleteTransaction(transaction)
    }

    suspend fun insertBudget(budget: Budget) {
        budgetDao.insertBudget(budget)
    }

    suspend fun deleteBudget(budget: Budget) {
        budgetDao.deleteBudget(budget)
    }

    suspend fun insertSavingsGoal(savingsGoal: SavingsGoal) {
        savingsGoalDao.insertSavingsGoal(savingsGoal)
    }

    suspend fun updateSavingsGoal(savingsGoal: SavingsGoal) {
        savingsGoalDao.updateSavingsGoal(savingsGoal)
    }

    suspend fun deleteSavingsGoal(savingsGoal: SavingsGoal) {
        savingsGoalDao.deleteSavingsGoal(savingsGoal)
    }

    suspend fun syncNow(): Result<SyncResult> {
        val currentUser = userProfile.value
        val txs = transactionDao.getAllTransactionsList()
        val budgets = budgetDao.getAllBudgetsList()
        val savings = savingsGoalDao.getAllSavingsGoalsList()

        return cloudSyncManager.syncWithCloud(
            userId = currentUser.id,
            localTransactions = txs,
            localBudgets = budgets,
            localSavingsGoals = savings
        )
    }

    suspend fun restoreFromCloud(): CloudDataBackup {
        val currentUser = userProfile.value
        val backup = cloudSyncManager.restoreFromCloud(currentUser.id)

        if (backup.transactions.isNotEmpty()) {
            transactionDao.insertTransactions(backup.transactions)
        }
        if (backup.budgets.isNotEmpty()) {
            budgetDao.insertBudgets(backup.budgets)
        }
        if (backup.savingsGoals.isNotEmpty()) {
            savingsGoalDao.insertSavingsGoals(backup.savingsGoals)
        }

        return backup
    }

    suspend fun signInWithGoogle(): Result<UserProfile> {
        val res = authManager.signInWithGoogle()
        if (res.isSuccess) {
            // Automatically sync/restore user cloud history after signing in
            restoreFromCloud()
        }
        return res
    }

    fun updateProfile(name: String, email: String, photoUrl: String?) {
        authManager.updateProfile(name, email, photoUrl)
    }

    fun signOut() {
        authManager.signOut()
    }
}
