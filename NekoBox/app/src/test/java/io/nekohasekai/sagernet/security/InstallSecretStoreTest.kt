package io.nekohasekai.sagernet.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import java.util.Base64

class InstallSecretStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun createsAndReusesA256BitSecret() {
        val directory = temporaryFolder.newFolder("no_backup")

        val first = InstallSecretStore(directory, SecureRandom()).get()
        val second = InstallSecretStore(directory, SecureRandom()).get()

        assertEquals(first, second)
        assertEquals(32, Base64.getUrlDecoder().decode(first).size)
        assertTrue(directory.resolve("clash_api.secret").isFile)
    }

    @Test
    fun separateInstallDirectoriesReceiveDifferentSecrets() {
        val first = InstallSecretStore(temporaryFolder.newFolder("install-a"), SecureRandom()).get()
        val second = InstallSecretStore(temporaryFolder.newFolder("install-b"), SecureRandom()).get()

        assertNotEquals(first, second)
    }
}