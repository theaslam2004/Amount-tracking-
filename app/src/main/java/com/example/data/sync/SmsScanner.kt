package com.example.data.sync

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import com.example.data.AppDatabase
import com.example.data.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

object SmsScanner {
    suspend fun syncRecentSms(context: Context, hoursBack: Int = 24) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return@withContext

        try {
            val timeAgo = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
            val cursor = context.contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                "date >= ?",
                arrayOf(timeAgo.toString()),
                "date ASC"
            )
            
            cursor?.use { c ->
                val bodyIndex = c.getColumnIndex("body")
                val addressIndex = c.getColumnIndex("address")
                
                val db = AppDatabase.getDatabase(context)
                
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
                            val desc = "Bank Sync: $address"
                            
                            // Check if it already exists to avoid duplicates
                            val existing = db.transactionDao().getAllTransactionsList().find { 
                                it.amount == amount && it.type == type && it.description == desc
                            }
                            
                            if (existing == null) {
                                db.transactionDao().insertTransaction(
                                    Transaction(
                                        amount = amount,
                                        description = desc,
                                        category = "Auto-Sync",
                                        type = type
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsScanner", "Error scanning SMS", e)
        }
    }
}
