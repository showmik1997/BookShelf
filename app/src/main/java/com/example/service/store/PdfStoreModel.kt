package com.example.service.store

data class StorePlatform(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val badge: String,
    val colorHex: String,
    val iconName: String
)

object StoreCatalog {
    val platforms = listOf(
        StorePlatform(
            id = "leanpub",
            name = "Leanpub",
            url = "https://leanpub.com/store/books",
            description = "Publishing platform offering DRM-free PDF and EPUB downloads directly from authors.",
            badge = "DRM-Free PDFs",
            colorHex = "#0284C7",
            iconName = "Code"
        ),
        StorePlatform(
            id = "packt",
            name = "Packt Publishing",
            url = "https://www.packtpub.com/",
            description = "Technical manuals, programming ebooks, and software architecture guides with PDF options.",
            badge = "Tech & Code",
            colorHex = "#EA580C",
            iconName = "MenuBook"
        ),
        StorePlatform(
            id = "google_play_books",
            name = "Google Play Books",
            url = "https://play.google.com/store/books",
            description = "Millions of titles, textbooks, and bestsellers. Direct PDF export supported for compatible titles.",
            badge = "Bestsellers",
            colorHex = "#2563EB",
            iconName = "Shop"
        ),
        StorePlatform(
            id = "amazon_books",
            name = "Amazon Books",
            url = "https://www.amazon.com/b?node=283155",
            description = "Global marketplace for textbooks, reference literature, and independent business publications.",
            badge = "Global Catalog",
            colorHex = "#D97706",
            iconName = "LocalMall"
        ),
        StorePlatform(
            id = "kobo",
            name = "Kobo Store",
            url = "https://www.kobo.com/",
            description = "Extensive international catalog of educational and narrative titles with Adobe DRM and open format support.",
            badge = "E-Reading",
            colorHex = "#DC2626",
            iconName = "Book"
        )
    )
}
