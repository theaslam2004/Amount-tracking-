package com.example.data.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody
                val sender = sms.displayOriginatingAddress ?: ""
                
                // Very basic regex to match "debited by Rs.500" or "Rs 500" or "INR 500"
                val regex = Regex("(?i)(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)")
                val match = regex.find(messageBody)
                
                val isDebit = messageBody.contains("debited", ignoreCase = true) || messageBody.contains("spent", ignoreCase = true)
                val isCredit = messageBody.contains("credited", ignoreCase = true) || messageBody.contains("deposited", ignoreCase = true)

                if (match != null && (isDebit || isCredit)) {
                    val amountString = match.groupValues[1].replace(",", "")
                    val amount = amountString.toDoubleOrNull()
                    
                    if (amount != null) {
                        val type = if (isDebit) "EXPENSE" else "INCOME"
                        val finalAmount = amount
                        val desc = "Bank: $sender"
                        
                        // Insert into DB
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val db = AppDatabase.getDatabase(context)
                                db.transactionDao().insertTransaction(
                                    Transaction(
                                        amount = finalAmount,
                                        description = desc,
                                        category = "Auto-Sync",
                                        type = type
                                    )
                                )
                                Log.d("SmsReceiver", "Auto-logged transaction: $type $finalAmount")
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }
}
