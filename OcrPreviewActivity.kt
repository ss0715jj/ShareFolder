package com.ibk.android.mbs.presentation.ui.ocr.id.preview

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.ibk.android.mbs.R
import com.ibk.android.mbs.common.IdCardImageBucket
import com.ibk.android.mbs.common.util.LogUtil
import com.ibk.android.mbs.databinding.ActivityOcrPreviewBinding
import com.ibk.android.mbs.presentation.base.BaseActivity
import com.ibk.android.mbs.presentation.common.UiState
import com.ibk.android.mbs.presentation.common.extension.collectRepeatOnStarted
import com.ibk.android.mbs.presentation.common.extension.setClickEvent
import com.ibk.android.mbs.presentation.dialog.CustomDialog
import com.ibk.android.mbs.presentation.ui.ocr.FacePointExtractor
import com.ibk.android.mbs.presentation.ui.ocr.PreviewErrorCallback
import com.ibk.android.mbs.presentation.ui.ocr.RecognitionType
import com.ibk.android.mbs.presentation.ui.ocr.ResultCode
import com.ibk.android.mbs.presentation.ui.ocr.overlay.IdCardTypeOverlayView
import com.inzisoft.mobile.camera.module.CameraAPILevelHelper
import com.inzisoft.mobile.data.MIDReaderProfile
import com.inzisoft.mobile.data.RecognizeResult
import com.inzisoft.mobile.recogdemolib.CameraPreviewInterface
import com.inzisoft.mobile.recogdemolib.LibConstants
import com.inzisoft.mobile.recogdemolib.RecognizeInterface
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * MBSC01022, FRQ-016
 * 신분증 촬영 프리뷰 화면
 * 신분증 촬영 프리뷰 출력 및 촬영 진행
 */
@AndroidEntryPoint
class OcrPreviewActivity : BaseActivity<ActivityOcrPreviewBinding>() {

    @Inject
    lateinit var facePointExtractor: FacePointExtractor

    private var overlayView: IdCardTypeOverlayView? = null

    private var cameraPreviewInterface: CameraPreviewInterface? = null

    // 수동촬영 모드에서 촬영 후 인식을 하기위해 사용
    private var recognizeInterface: RecognizeInterface? = null

    private val viewModel: OcrPreviewViewModel by viewModels()

    override var layoutResourceId: Int = R.layout.activity_ocr_preview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 화면 꺼짐 방지

        collectData()
        initView()
        initCameraPreviewInterface()
        initFacePointExtractor()
    }

    private fun collectData() {
        lifecycleScope.launch {
            viewModel.showErrorConfirmDialog.collectLatest {
                showErrorConfirmDialog(it)
            }
        }

        viewModel.processingResult.collectRepeatOnStarted(this) {
            when (it) {
                is UiState.Loading -> {
                    showLoading()
                }

                is UiState.Success -> {
                    hideLoading()
                    setResult(ResultCode.RESULT_ID_CARD_OK, Intent().putExtras(it.data))
                    finish()
                }

                is UiState.Error -> {
                    Log.e("JIN", "error", it.exception)
                    hideLoading()
                    setResult(ResultCode.RESULT_RECOGNIZE_FAILED)
                    finish()
                }
            }
        }
    }

    private fun initView() {
        binding.closeButton.setClickEvent(lifecycleScope) {
            onBackPressedCallback()
        }
    }

    private fun initCameraPreviewInterface() {
        MIDReaderProfile.getInstance().apply {
            SET_USER_SCREEN_PORTRAIT = false
            USE_MOTION_DETECTION = false
            if (CameraAPILevelHelper.isSupportAPI28()) {
                USE_DEEP_LEARNING_AUTO_CROP = true
                PASS_FRAMES_PER_USE = 1
            } else {
                USE_DEEP_LEARNING_AUTO_CROP = false
                PASS_FRAMES_PER_USE = 5
            }
        }

        overlayView = IdCardTypeOverlayView(this).apply {
            setPreviewErrorCallback(previewErrorCallback)
        }
        cameraPreviewInterface = CameraPreviewInterface(this, moveToRecognizeActivityListener)
            .apply {
                setPictureDesireResolution(3000000)
                setRecogType(LibConstants.TYPE_IDCARD_BACK) // 인식 타입
                setTakePictureDelayTime(0)
                // 자동촬영 인식 후 포커스가 잡히지 않은 상태로 촬영을 해서 추출이 안되는 경우 발생
                // 딜레이를 주어 포커스가 잡힌 후 촬영 되도록 조정
                setAutoCaptureWaitTime(1000)

                if (viewModel.enablePreviewRecognize) {
                    setPreviewRecognizeListener(previewRecognizeCheckListener)
                    setPreviewPictureRecogEnable(true)
                }
            }.also {
                it.initLayout(
                    binding.cameraButton,
                    overlayView,
                    binding.cameraPreviewLayout.cameraPreview
                )
                it.onCreate()
            }
    }

    private fun initRecognizeInterface(roi: Rect) {
        recognizeInterface = RecognizeInterface(
            this,
            roi,
            viewModel.recognitionType.type,
            recognizeFinishListener
        )
        recognizeInterface?.startRecognizeAutoCrop()
        showLoading()
    }

    private fun initFacePointExtractor() {
        lifecycleScope.launch(Dispatchers.IO) {
            facePointExtractor.init()
        }
    }

    private fun showErrorConfirmDialog(errorType: PreviewErrorType) {
        cameraPreviewInterface?.onPause(true)
        CustomDialog(
            message = errorType.message,
            positiveButtonText = getString(R.string.dialog_button_manual),
            negativeButtonText = getString(R.string.dialog_button_retry),
        ).showConfirm(supportFragmentManager) { button ->
            when (button) {
                CustomDialog.ButtonWhich.POSITIVE -> {
                    // 수동촬영인 경우 preview error 콜백을 받지 않도록 설정
                    overlayView?.setPreviewErrorCallback(null)
                    cameraPreviewInterface?.let {
                        it.initLayout(
                            binding.cameraButton,
                            overlayView,
                            binding.cameraPreviewLayout.cameraPreview
                        )
                        it.setPreviewPictureRecogEnable(false)
                        it.setAutoCaptureEnable(false)
                        it.onCreate()
                    }
                }

                CustomDialog.ButtonWhich.NEGATIVE -> {
                    cameraPreviewInterface?.clearPreviewRecogFailCnt()
                }

                else -> {

                }
            }

            if (!RecognizeResult.getInstance().enableNextShot()) {
                RecognizeResult.getInstance().clean()
            }

            viewModel.clearPreviewFailedCount()
            cameraPreviewInterface?.resumeCameraPreview()
            cameraPreviewInterface?.setContinuePreviewRecognition()
        }
    }

    private fun showBackSideTakeGuideDialog() {
        cameraPreviewInterface?.onPause(true)
        CustomDialog(
            message = getString(R.string.guide_msg_take_back_side),
        ).show(supportFragmentManager) {
            cameraPreviewInterface?.let {
                it.setRecogType(RecognitionType.ID_CARD_BACK.type)
                it.resumeCameraPreview()
                it.setContinuePreviewRecognition()
            }
        }
    }

    private fun checkTakeBackSide() {
        if (!viewModel.enableTakeBackSide) {
            IdCardImageBucket.setFrontIdCardImage(RecognizeResult.getInstance().getRecogResultImage(false))
            viewModel.startProcessingResult(applicationContext, facePointExtractor)
            return
        }

        if (IdCardImageBucket.getFrontIdCardImage() == null) { // 앞면 처리
            IdCardImageBucket.setFrontIdCardImage(RecognizeResult.getInstance().getRecogResultImage(false))
            lifecycleScope.launch(Dispatchers.Main) { showBackSideTakeGuideDialog() }
        } else {
            IdCardImageBucket.setBackIdCardImage(RecognizeResult.getInstance().getRecogResultImage(false))
            viewModel.startProcessingResult(applicationContext, facePointExtractor)
        }
    }

    override fun onBackPressedCallback() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraPreviewInterface?.onResume()
    }

    override fun onResume() {
        super.onResume()
        cameraPreviewInterface?.onResume()
    }

    override fun onPause() {
        super.onPause()
        cameraPreviewInterface?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        facePointExtractor.release()
        cameraPreviewInterface?.release()
        recognizeInterface?.onDestroy()
    }

    private val previewErrorCallback = object : PreviewErrorCallback {

        override fun findEdgeFailed() = viewModel.handleFoundEdgeFailed()

        override fun reflection() = viewModel.handleReflectionFailed()

        override fun recogFailed() = viewModel.handleRecogFailed()
    }

    // 수동 촬영 완료 콜백
    private val moveToRecognizeActivityListener =
        object : CameraPreviewInterface.MoveToRecognizeActivityListener {
            override fun callback(pictureROI: Rect, resultCode: Int) {
                when (resultCode) {
                    LibConstants.LICENSE_RECOGNITION_TYPE_FAIL -> {}
                    LibConstants.ERR_CODE_TAKE_PICTURE_FAILED -> {
                        Toast.makeText(
                            this@OcrPreviewActivity,
                            getString(R.string.error_message_failed_take),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> {
                        initRecognizeInterface(pictureROI)
                    }
                }
            }

            override fun onCameraStarted() {}
        }

    // 프리뷰 인식 콜백
    private val previewRecognizeCheckListener =
        object : CameraPreviewInterface.PreviewRecognizeCheckListener {
            override fun onRecognitionStarted() {}

            override fun onRecognitionEnded() {
                cameraPreviewInterface?.onPause(true)
                checkTakeBackSide()
            }

            override fun onCheckValidation(): Boolean {
                val idCardResult = RecognizeResult.getInstance().idCardRecognizeResult
                val nameLength = idCardResult.getNameLength(applicationContext)
                val licenseLength = idCardResult.getLicenseNumberLength(applicationContext)
                val dateLength = idCardResult.getDateLength(applicationContext)

                return nameLength > 1 && (licenseLength >= 11 || dateLength == 8)
            }

            override fun onRecognitionFailed(type: Int, resultCode: Int) {
                Log.d("JIN", "onRecognitionFailed ----- type : $type, resultCode : $resultCode")
                lifecycleScope.launch(Dispatchers.Main) {
                    showErrorConfirmDialog(PreviewErrorType.ERROR_OVER_PREVIEW_FAIL_LIMIT)
                }
            }
        }

    // 수동촬영 인식 완료 시 호출 콜백
    private val recognizeFinishListener =
        RecognizeInterface.RecognizeFinishListener { resultValue ->
            LogUtil.d("recognized result code : $resultValue") // -2130509811 반환
            hideLoading()

            when (resultValue) {
                LibConstants.ERR_CODE_RECOGNITION_SUCCESS -> {
                    // 무조건 인식이 완료(성공)가 된 후 호출
                    checkTakeBackSide()
                }

                else -> {
                    setResult(ResultCode.RESULT_RECOGNIZE_FAILED)
                    finish()
                }
            }
        }
}