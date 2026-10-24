package com.example.sleppify

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class WelcomeAuthFragment : Fragment() {

    private var btnWelcomeGoogle: MaterialButton? = null
    private var progressWelcomeGoogle: ProgressBar? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_welcome_auth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val clContent = view.findViewById<View>(R.id.clWelcomeContent)
        btnWelcomeGoogle = view.findViewById(R.id.btnWelcomeGoogle)
        progressWelcomeGoogle = view.findViewById(R.id.progressWelcomeGoogle)

        // Respetar insets del sistema (Status bar y Navigation bar)
        if (clContent != null) {
            ViewCompat.setOnApplyWindowInsetsListener(clContent) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(
                    v.paddingLeft,
                    systemBars.top + (8 * resources.displayMetrics.density).toInt(),
                    v.paddingRight,
                    systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
                )
                insets
            }
            clContent.requestApplyInsets()
        }

        btnWelcomeGoogle?.setOnClickListener {
            handleGoogleSignInClicked()
        }
    }

    private fun handleGoogleSignInClicked() {
        val mainActivity = activity as? MainActivity ?: return
        val btn = btnWelcomeGoogle ?: return

        btn.isEnabled = false
        btn.alpha = 0.65f
        btn.text = ""
        progressWelcomeGoogle?.visibility = View.VISIBLE

        mainActivity.startGoogleSignInFromWelcome(
            onSuccess = {
                // Al completarse con éxito, MainActivity se encarga de despedir el onboarding
            },
            onError = {
                resetGoogleButtonState()
            }
        )
    }

    fun resetGoogleButtonState() {
        if (!isAdded) return
        activity?.runOnUiThread {
            val btn = btnWelcomeGoogle ?: return@runOnUiThread
            btn.isEnabled = true
            btn.alpha = 1f
            btn.text = "Continuar con Google"
            progressWelcomeGoogle?.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        btnWelcomeGoogle = null
        progressWelcomeGoogle = null
    }

    companion object {
        const val TAG = "WelcomeAuthFragment"
    }
}
