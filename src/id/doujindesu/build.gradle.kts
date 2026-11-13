import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Doujindesu"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    pkgName = "id.ayounkworks.doujindesupersonal"

    source {
        lang = "id"
        baseUrl {
            custom("https://doujin.desu.xxx")
        }
        id = 7704282043609669342L
    }

    deeplink {
        path("/manga/..*")
        path("/reader/..*")
    }
}
