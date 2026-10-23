import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "VoraToon Personal"
    // Unique application ID suffix so this private build can coexist with the official source.
    pkgName = "id.ayounkworks.voratoonpersonal"
    versionCode = 88
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
        // Distinct source ID for this personal fork.
        id = 158530748440709235L
    }
}
