package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutStunBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import libcore.Libcore

class StunActivity : ThemedActivity() {

    private lateinit var binding: LayoutStunBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutStunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.stun_test)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.stunTest.setOnClickListener {
            doTest()
        }
    }

    fun doTest() {
        binding.waitLayout.isVisible = true
        runOnDefaultDispatcher {
            val result = try {
                val _result = Libcore.stunTest(binding.natStunServer.text.toString())
                if (_result != null && _result.success) {
                    _result.text
                } else {
                    throw Exception(_result?.text ?: "STUN test returned empty result")
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    if (isFinishing || isDestroyed) return@onMainDispatcher
                    AlertDialog.Builder(this@StunActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(moe.matsuri.nb4a.Protocols.sanitizeErrorMessage(e.readableMessage))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            finish()
                        }
                        .setOnCancelListener {
                            finish()
                        }
                        .runCatching { show() }
                }
                return@runOnDefaultDispatcher
            }
            onMainDispatcher {
                if (isFinishing || isDestroyed) return@onMainDispatcher
                binding.waitLayout.isVisible = false
                binding.natResult.text = result
            }
        }
    }

}