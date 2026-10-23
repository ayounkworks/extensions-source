import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "VoraToon Personal"
    pkgName = "id.ayounkworks.voratoon"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    deeplink {
        path("/series/..*")
    }

    source {
        lang = "id"
        baseUrl {
            custom("https://v10.voratoon.com")
        }
        id = 10430526313348178L
    }
}
