import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "VoraToon"
    // Unique application ID suffix so this private build can coexist with the official source.
    pkgName = "id.ayounkworks.voratoonpersonal"
    versionCode = 99
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    deeplink {
        path("/series/..*")
    }

    source {
        lang = "id"
        baseUrl {
            custom("https://voratoon.co")
        }
        // Keep the original source ID to preserve the existing library association.
        id = 972717448578983812L
    }
}
