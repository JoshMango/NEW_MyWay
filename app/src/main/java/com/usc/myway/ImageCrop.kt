// Crop options for the three images users upload. The cropper library owns the gesture UI;
// we only pick the frame. Results come back as a temp Uri → feed straight into encodeImage().
package com.usc.myway

import android.graphics.Bitmap
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

private fun options(build: CropImageOptions.() -> Unit) = CropImageContractOptions(
    null, // null source → the library opens the gallery picker itself
    CropImageOptions(
        imageSourceIncludeCamera = false,
        fixAspectRatio = true,
        // Force high-contrast colors for the toolbar and "Done" menu item
        toolbarColor = 0xFF00A77D.toInt(),        // TealDeep
        toolbarTitleColor = 0xFFFFFFFF.toInt(),   // White
        toolbarBackButtonColor = 0xFFFFFFFF.toInt(),
        activityMenuIconColor = 0xFFFFFFFF.toInt(),
        cropMenuCropButtonTitle = "Save",         // Explicit text label
        
        outputCompressFormat = Bitmap.CompressFormat.JPEG,
        outputCompressQuality = 90,
        guidelines = CropImageView.Guidelines.ON_TOUCH,
    ).apply(build),
)

/** Square, circular overlay — profile and group avatars. */
fun avatarCropOptions() = options {
    aspectRatioX = 1; aspectRatioY = 1
    cropShape = CropImageView.CropShape.OVAL
}

/** Wide Discord-style cover. */
fun bannerCropOptions() = options {
    aspectRatioX = 16; aspectRatioY = 6
    cropShape = CropImageView.CropShape.RECTANGLE
}
