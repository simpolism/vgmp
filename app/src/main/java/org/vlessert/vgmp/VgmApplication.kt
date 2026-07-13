package org.vlessert.vgmp

import android.app.Application
import org.vlessert.vgmp.library.GameLibrary

class VgmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Room remains available for legacy installs, but browsing and playlists
        // never scan or import the user's filesystem into it.
        GameLibrary.init(this)
    }
}
