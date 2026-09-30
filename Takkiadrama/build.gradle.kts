plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "Takkiadrama Arabic streaming provider"
    authors = listOf("sagemoon1996")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie"
    )
    language = "ar"
}

android {
    namespace = "com.sagemoon1996.takkiadrama"
}
