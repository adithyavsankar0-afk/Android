/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.favicon.FaviconManager
import com.duckduckgo.autofill.api.AutofillImportBookmarksLaunchSource
import com.duckduckgo.autofill.api.AutofillImportLaunchSource
import com.duckduckgo.autofill.api.AutofillImportLaunchSource.Unknown
import com.duckduckgo.autofill.impl.R
import com.duckduckgo.autofill.impl.databinding.ContentImportFromGoogleBookmarkDialogBinding
import com.duckduckgo.autofill.impl.deviceauth.AutofillAuthorizationGracePeriod
import com.duckduckgo.autofill.impl.importing.CredentialImporter
import com.duckduckgo.autofill.impl.importing.takeout.webflow.ImportGoogleBookmark.AutofillImportViaGoogleTakeoutScreen
import com.duckduckgo.autofill.impl.importing.takeout.webflow.ImportGoogleBookmarkResult
import com.duckduckgo.autofill.impl.ui.credential.dialog.animateClosed
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialog.ImportBookmarksDialog.Companion.KEY_IMPORT_SUCCESS
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialog.ImportBookmarksDialog.Companion.KEY_TAB_ID
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialog.ImportBookmarksDialog.Companion.KEY_URL
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.BrowserPromoPreImport
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.DeterminingFirstView
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.FlowTerminated
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.ImportError
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.ImportSuccess
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.Importing
import com.duckduckgo.autofill.impl.ui.credential.management.importbookmark.google.ImportFromGoogleBookmarksDialogViewModel.ViewMode.PreImport
import com.duckduckgo.autofill.impl.ui.credential.management.importpassword.ImportPasswordsPixelSender
import com.duckduckgo.common.ui.view.gone
import com.duckduckgo.common.ui.view.prependIconToText
import com.duckduckgo.common.ui.view.show
import com.duckduckgo.common.utils.FragmentViewModelFactory
import com.duckduckgo.common.utils.extensions.html
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.navigation.api.GlobalActivityStarter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority.ERROR
import logcat.LogPriority.VERBOSE
import logcat.asLog
import logcat.logcat
import javax.inject.Inject

@InjectWith(FragmentScope::class)
class ImportFromGoogleBookmarksDialog : BottomSheetDialogFragment() {
    @Inject
    lateinit var importPasswordsPixelSender: ImportPasswordsPixelSender

    /**
     * To capture all the ways the BottomSheet can be dismissed, we might end up with onCancel being called when we don't want it
     * This flag is set to true when taking an action which dismisses the dialog, but should not be treated as a cancellation.
     */
    private var ignoreCancellationEvents = false

    override fun getTheme(): Int = R.style.AutofillBottomSheetDialogTheme

    @Inject
    lateinit var faviconManager: FaviconManager

    @Inject
    lateinit var globalActivityStarter: GlobalActivityStarter

    @Inject
    lateinit var authorizationGracePeriod: AutofillAuthorizationGracePeriod

    private var _binding: ContentImportFromGoogleBookmarkDialogBinding? = null

    val binding get() = _binding!!

    @Inject
    lateinit var viewModelFactory: FragmentViewModelFactory

    private val viewModel by bindViewModel<ImportFromGoogleBookmarksDialogViewModel>()

    private var successImport = false

    private fun getTabId(): String? = arguments?.getString(KEY_TAB_ID)

    private val importTakeoutFlowLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch {
                    activityResult.data?.let { data ->
                        val launchSource = getLaunchSource()
                        processImportFlowResult(data, launchSource)
                    }
                }
            }
        }

    private fun getLaunchSource() =
        BundleCompat.getParcelable(arguments ?: Bundle(), KEY_LAUNCH_SOURCE, AutofillImportLaunchSource::class.java) ?: Unknown

    private fun ImportFromGoogleBookmarksDialog.processImportFlowResult(
        data: Intent,
        launchSource: AutofillImportLaunchSource,
    ) {
        (IntentCompat.getParcelableExtra(data, ImportGoogleBookmarkResult.RESULT_KEY_DETAILS, ImportGoogleBookmarkResult::class.java)).let {
            when (it) {
                is ImportGoogleBookmarkResult.Success -> viewModel.onImportFlowFinishedSuccessfully(launchSource)
                is ImportGoogleBookmarkResult.Error -> viewModel.onImportFlowFinishedWithError(it.reason, launchSource)
                is ImportGoogleBookmarkResult.UserCancelled ->
                    viewModel.onImportFlowCancelledByUser(
                        it.stage,
                        launchSource,
                    )
                else -> {}
            }
        }
    }

    private fun switchDialogShowImportInProgressView() {
        showDialogContent()

        binding.prePostViewSwitcher.displayedChild = 1
        binding.postflow.inProgressFinishedViewSwitcher.displayedChild = 0
    }

    private fun switchDialogShowImportResultsView() {
        binding.prePostViewSwitcher.displayedChild = 1
        binding.postflow.inProgressFinishedViewSwitcher.displayedChild = 1
    }

    private fun switchDialogShowPreImportView() {
        with(binding.preflow) {
            dialogTitle.text = getString(R.string.importPasswordsChooseMethodDialogTitle)
            onboardingSubtitle.text = getString(R.string.importPasswordsChooseMethodDialogSubtitle)
            declineButton.gone()
            topIllustrationAnimated.gone()
            appIcon.show()
        }
        showDialogContent()
        binding.prePostViewSwitcher.displayedChild = 0
    }

    private fun showPreImportView() {
        with(binding.preflow) {
            dialogTitle.text = getString(R.string.passwords_import_promo_in_browser_title)
            onboardingSubtitle.text = binding.root.context.prependIconToText(R.string.passwords_import_promo_subtitle, R.drawable.ic_lock_solid_12)
            declineButton.show()
            topIllustrationAnimated.setAnimation(R.raw.anim_password_keys)
            topIllustrationAnimated.show()
            topIllustrationAnimated.playAnimation()
            appIcon.gone()
        }
        showDialogContent()
        binding.prePostViewSwitcher.displayedChild = 0
    }

    private fun processSuccessResult(result: CredentialImporter.ImportResult.Finished) {
        successImport = true
        showDialogContent()

        binding.postflow.importFinished.errorNotImported.visibility = View.GONE
        binding.postflow.appIcon.setImageDrawable(
            ContextCompat.getDrawable(
                binding.root.context,
                R.drawable.ic_success_128,
            ),
        )
        binding.postflow.dialogTitle.text = getString(R.string.importPasswordsProcessingResultDialogTitleUponSuccess)

        with(binding.postflow.importFinished.resultsImported) {
            val output = getString(R.string.importPasswordsProcessingResultDialogResultPasswordsImported, result.savedCredentials)
            setPrimaryText(output.html(binding.root.context))
        }

        with(binding.postflow.importFinished.duplicatesNotImported) {
            val output = getString(R.string.importPasswordsProcessingResultDialogResultDuplicatesSkipped, result.numberSkipped)
            setPrimaryText(output.html(binding.root.context))
            visibility = if (result.numberSkipped > 0) View.VISIBLE else View.GONE
        }

        switchDialogShowImportResultsView()
    }

    private fun processErrorResult() {
        showDialogContent()

        binding.postflow.importFinished.resultsImported.visibility = View.GONE
        binding.postflow.importFinished.duplicatesNotImported.visibility = View.GONE
        binding.postflow.importFinished.errorNotImported.visibility = View.VISIBLE

        binding.postflow.dialogTitle.text = getString(R.string.importPasswordsProcessingResultDialogTitleBeforeSuccess)
        binding.postflow.appIcon.setImageDrawable(
            ContextCompat.getDrawable(
                binding.root.context,
                R.drawable.ic_passwords_import_128,
            ),
        )

        switchDialogShowImportResultsView()
    }

    private fun showDialogContent() {
        binding.root.show()
    }

    private fun hideDialogContent() {
        binding.root.gone()
    }

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            // If being created after a configuration change, dismiss the dialog as the WebView will be re-created too
            dismiss()
            return
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = ContentImportFromGoogleBookmarkDialogBinding.inflate(inflater, container, false)
        configureViews(binding)
        observeViewModel()
        logcat { "Creating ImportFromGooglePasswordsDialog with launch source: ${getLaunchSource()}" }
        showPreImportView()
        return binding.root
    }

    private fun observeViewModel() {
        viewModel.viewState
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { viewState -> renderViewState(viewState) }
            .launchIn(lifecycleScope)
    }

    private fun renderViewState(viewState: ImportFromGoogleBookmarksDialogViewModel.ViewState) {
        when (viewState.viewMode) {
            is PreImport -> switchDialogShowPreImportView()
            is BrowserPromoPreImport -> showPreImportView()
            is ImportError -> processErrorResult()
            is ImportSuccess -> processSuccessResult(viewState.viewMode.importResult)
            is Importing -> switchDialogShowImportInProgressView()
            is DeterminingFirstView -> hideDialogContent()
            is FlowTerminated -> dismiss()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        logcat { "Dismissing ${javaClass.simpleName}, successful import? $successImport" }
        setResult(successImport)
    }

    private fun setResult(successImport: Boolean) {
        logcat { "Autofill-import: Setting result for ${javaClass.simpleName}, successful import? $successImport. tabId=${getTabId()}" }

        getTabId()?.let { tabId ->
            val result =
                Bundle().also {
                    it.putBoolean(KEY_IMPORT_SUCCESS, successImport)
                    it.putString(KEY_URL, getOriginalUrl())
                }

            val resultKey = ImportBookmarksDialog.resultKey(tabId)
            findCorrectFragmentManager()?.setFragmentResult(resultKey, result)
        }
    }

    private fun findCorrectFragmentManager(): FragmentManager? {
        val result =
            runCatching {
                parentFragment?.parentFragmentManager ?: parentFragmentManager
            }.onFailure { logcat(ERROR) { "Autofill-import: Failed to find a valid fragment manager ${it.asLog()}" } }

        return result.getOrNull()
    }

    override fun onDestroyView() {
        _binding = null
        authorizationGracePeriod.removeRequestForExtendedGracePeriod()
        super.onDestroyView()
    }

    private fun configureViews(binding: ContentImportFromGoogleBookmarkDialogBinding) {
        (dialog as BottomSheetDialog).behavior.state = BottomSheetBehavior.STATE_EXPANDED
        configureCloseButton(binding)

        with(binding.preflow.importGcmButton) {
            setOnClickListener { onImportGcmButtonClicked() }
        }

        with(binding.postflow.importFinished.primaryCtaButton) {
            setOnClickListener {
                dismiss()
            }
        }

        binding.preflow.declineButton.setOnClickListener {
            viewModel.onPreflowDialogDismissed()
        }
    }

    private fun onImportGcmButtonClicked() {
        startImportWebFlow()
        importPasswordsPixelSender.onImportPasswordsDialogImportButtonClicked(getLaunchSource())
    }

    private fun startImportWebFlow() {
        authorizationGracePeriod.requestExtendedGracePeriod()

        val intent =
            globalActivityStarter.startIntent(
                requireContext(),
                AutofillImportViaGoogleTakeoutScreen,
            )
        importTakeoutFlowLauncher.launch(intent)

        // we don't want the eventual dismissal of this dialog to count as a cancellation
        ignoreCancellationEvents = true
    }

    override fun onCancel(dialog: DialogInterface) {
        logcat {
            "Cancelling ImportFromGooglePasswordsDialog, " +
                "successful import? $successImport. " +
                "ignore cancellation events: $ignoreCancellationEvents"
        }

        if (ignoreCancellationEvents) {
            logcat(VERBOSE) { "onCancel: Ignoring cancellation event" }
            return
        }

        importPasswordsPixelSender.onUserCancelledImportPasswordsDialog(getLaunchSource())
    }

    private fun configureCloseButton(binding: ContentImportFromGoogleBookmarkDialogBinding) {
        binding.closeButton.setOnClickListener { (dialog as BottomSheetDialog).animateClosed() }
    }

    private fun getOriginalUrl() = arguments?.getString(KEY_URL)

    private inline fun <reified V : ViewModel> bindViewModel() = lazy { ViewModelProvider(this, viewModelFactory)[V::class.java] }

    companion object {
        private const val KEY_LAUNCH_SOURCE = "launchSource"

        fun instance(
            importSource: AutofillImportBookmarksLaunchSource,
            tabId: String? = null,
            originalUrl: String? = null,
        ): ImportFromGoogleBookmarksDialog {
            val fragment = ImportFromGoogleBookmarksDialog()
            fragment.arguments =
                Bundle().apply {
                    putParcelable(KEY_LAUNCH_SOURCE, importSource)
                    putString(KEY_TAB_ID, tabId)
                    putString(KEY_URL, originalUrl)
                }
            return fragment
        }
    }

    interface ImportBookmarksDialog {
        companion object {
            fun resultKey(tabId: String) = "${prefix(tabId, TAG)}/Result"

            const val TAG = "ImportBookmarksDialog"
            const val KEY_URL = "url"
            const val KEY_IMPORT_SUCCESS = "importSuccess"
            const val KEY_TAB_ID = "tabId"

            private fun prefix(
                tabId: String,
                tag: String,
            ): String = "$tabId/$tag"
        }
    }
}
