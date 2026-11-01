package com.lencodigitexer.printables.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lencodigitexer.printables.R
import com.lencodigitexer.printables.api.PrintablesApiClient
import com.lencodigitexer.printables.data.FavoritesManager
import com.lencodigitexer.printables.data.ModelFile
import com.lencodigitexer.printables.data.PrintableModel
import com.lencodigitexer.printables.databinding.BottomSheetModelDetailsBinding
import com.lencodigitexer.printables.databinding.ItemModelFileBinding
import com.lencodigitexer.printables.util.PrintablesImageLoader
import kotlinx.coroutines.launch

class ModelDetailBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetModelDetailsBinding? = null
    private val binding get() = _binding!!

    private var model: PrintableModel? = null
    private val apiClient = PrintablesApiClient()
    private lateinit var favoritesManager: FavoritesManager

    var onBookmarkChanged: ((PrintableModel) -> Unit)? = null

    companion object {
        fun newInstance(model: PrintableModel): ModelDetailBottomSheet {
            val sheet = ModelDetailBottomSheet()
            sheet.model = model
            return sheet
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetModelDetailsBinding.inflate(inflater, container, false)
        favoritesManager = FavoritesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val m = model ?: return

        setupModelInfo(m)
        setupActions(m)
        loadFiles(m)
    }

    private fun setupModelInfo(m: PrintableModel) {
        val imageLoader = PrintablesImageLoader.getInstance(requireContext())

        binding.tvSheetTitle.text = m.name
        binding.tvSheetAuthor.text = m.author ?: "Printables Community"
        binding.tvSheetAuthorHandle.text = if (!m.authorHandle.isNullOrBlank()) "@${m.authorHandle}" else "@printables"

        val initial = m.author?.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
        binding.tvSheetAuthorAvatar.text = initial

        binding.tvSheetRating.text = m.formattedRating
        binding.tvSheetLikes.text = m.formattedLikes
        binding.tvSheetDownloads.text = m.formattedDownloads

        binding.tvSheetDescription.text = m.description ?: "High quality 3D model shared on Printables.com. Inspect files below for STLs and pre-sliced G-codes for Prusa 3D printers."

        // Cover Image
        imageLoader.load(
            url = m.mainImageUrl,
            imageView = binding.ivSheetCover,
            placeholderRes = R.drawable.bg_image_placeholder,
            scope = viewLifecycleOwner.lifecycleScope
        )

        updateBookmarkButton(m.isBookmarked)
    }

    private fun updateBookmarkButton(isBookmarked: Boolean) {
        if (isBookmarked) {
            binding.btnSheetBookmark.text = "Saved"
            binding.btnSheetBookmark.setIconResource(R.drawable.ic_bookmark_filled)
            binding.btnSheetBookmark.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.printables_orange)
        } else {
            binding.btnSheetBookmark.text = "Save"
            binding.btnSheetBookmark.setIconResource(R.drawable.ic_bookmark)
            binding.btnSheetBookmark.iconTint = ContextCompat.getColorStateList(requireContext(), R.color.text_primary)
        }
    }

    private fun setupActions(m: PrintableModel) {
        binding.btnSheetClose.setOnClickListener {
            dismiss()
        }

        binding.btnSheetShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, m.name)
                putExtra(Intent.EXTRA_TEXT, "${m.name}\n${m.modelUrl}")
            }
            startActivity(Intent.createChooser(shareIntent, "Share 3D Model"))
        }

        binding.btnSheetOpenWeb.setOnClickListener {
            openUrl(m.modelUrl)
        }

        binding.btnSheetOpenInBrowser.setOnClickListener {
            openUrl(m.modelUrl)
        }

        binding.btnSheetBookmark.setOnClickListener {
            val isNowBookmarked = favoritesManager.toggleBookmark(m)
            updateBookmarkButton(isNowBookmarked)
            onBookmarkChanged?.invoke(m)
            val msg = if (isNowBookmarked) "Saved to Bookmarks" else "Removed from Bookmarks"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnExportSingleJson.setOnClickListener {
            val json = m.toPrintablesApiJson().toString(2)
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Printables Model JSON", json)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFiles(m: PrintableModel) {
        if (m.files.isNotEmpty()) {
            renderFilesList(m, m.files)
            return
        }

        binding.progressFiles.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val files = apiClient.getModelFiles(m.id)
                m.files = files
                binding.progressFiles.visibility = View.GONE
                renderFilesList(m, files)
            } catch (e: Exception) {
                binding.progressFiles.visibility = View.GONE
            }
        }
    }

    private fun renderFilesList(m: PrintableModel, files: List<ModelFile>) {
        binding.layoutFilesList.removeAllViews()

        if (files.isEmpty()) {
            val emptyTv = android.widget.TextView(requireContext()).apply {
                text = "No files found for this model."
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
                textSize = 13f
            }
            binding.layoutFilesList.addView(emptyTv)
            return
        }

        binding.tvSheetFilesHeader.text = "${getString(R.string.files)} (${files.size})"

        for (file in files) {
            val fileBinding = ItemModelFileBinding.inflate(
                layoutInflater,
                binding.layoutFilesList,
                false
            )

            fileBinding.tvFileName.text = file.name
            fileBinding.tvFileMeta.text = file.subtitle

            // Set file type icon color
            val isGcode = file.fileType.equals("gcode", ignoreCase = true)
            val tintColor = ContextCompat.getColor(
                requireContext(),
                if (isGcode) R.color.gcode_green else R.color.printables_orange
            )
            fileBinding.ivFileType.setColorFilter(tintColor)

            fileBinding.btnDownloadFile.setOnClickListener {
                fileBinding.btnDownloadFile.isEnabled = false
                fileBinding.btnDownloadFile.text = "Fetching..."

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val realUrl = file.downloadUrl
                            ?: apiClient.getRealDownloadUrl(file.id, m.id, file.fileType)

                        file.downloadUrl = realUrl
                        fileBinding.btnDownloadFile.isEnabled = true
                        fileBinding.btnDownloadFile.text = getString(R.string.download_file)

                        if (!realUrl.isNullOrBlank()) {
                            openUrl(realUrl)
                        } else {
                            openUrl(m.modelUrl)
                        }
                    } catch (e: Exception) {
                        fileBinding.btnDownloadFile.isEnabled = true
                        fileBinding.btnDownloadFile.text = getString(R.string.download_file)
                        openUrl(m.modelUrl)
                    }
                }
            }

            binding.layoutFilesList.addView(fileBinding.root)
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
