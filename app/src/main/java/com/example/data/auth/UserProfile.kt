package com.example.data.auth

data class UserProfile(
    val id: String = "google_user_aslam",
    val name: String = "Aslam",
    val email: String = "aslamarasaad818181@gmail.com",
    val photoUrl: String? = null,
    val isSignedIn: Boolean = true,
    val isGoogleLinked: Boolean = true
)
