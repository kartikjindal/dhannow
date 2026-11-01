package com.navix.storage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.storage.config.StorageCategory;
import com.navix.storage.config.StorageProperties;
import com.navix.storage.service.DocumentStorageService;
import com.navix.storage.web.StorageDtos.PresignUploadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Each presign route is narrowed to the roles (and, for downloads, the key prefix) its real callers use. */
@ExtendWith(MockitoExtension.class)
class StorageControllerTest {

    @Mock
    private DocumentStorageService storage;

    private StorageController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageController(storage, new StorageProperties("bucket", 900, null));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void as(String role) {
        ActorContext.set(new CurrentActor("1", "t", role));
    }

    @Test
    void borrowerMayUploadARepaymentProof() {
        as("BORROWER");
        when(storage.buildKey(any(), anyString())).thenReturn("loan/repayment-proof/x-a.jpg");
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://s3/put");

        var res = controller.presignUpload(new PresignUploadRequest(StorageCategory.REPAYMENT_PROOF, "a.jpg", "image/jpeg"));

        assertThat(res.isSuccess()).isTrue(); // PLAN Phase 11: the ApiResponse envelope, like every other route
        assertThat(res.getData().key()).isEqualTo("loan/repayment-proof/x-a.jpg");
    }

    /** PLAN Phase 11: a repayment proof is PDF, JPG or PNG; a lead file keeps its CSV/XLSX. */
    @Test
    void aRepaymentProofMustBeAPdfJpgOrPng() {
        as("BORROWER");
        assertThatThrownBy(() -> controller.presignUpload(
                new PresignUploadRequest(StorageCategory.REPAYMENT_PROOF, "a.heic", "image/heic")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_FILE_TYPE");
        verifyNoInteractions(storage);

        as("TELECALLER");
        when(storage.buildKey(any(), anyString())).thenReturn("leads/import/x-a.csv");
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://s3/put");
        assertThat(controller.presignUpload(new PresignUploadRequest(StorageCategory.LEAD_IMPORT, "a.csv", "text/csv"))
                .getData().key()).isEqualTo("leads/import/x-a.csv");
    }

    @Test
    void borrowerCannotUploadAdminAssets() {
        as("BORROWER");
        assertThatThrownBy(() -> controller.presignUpload(
                new PresignUploadRequest(StorageCategory.PAYMENT_SETTINGS, "qr.png", "image/png")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
        verifyNoInteractions(storage);
    }

    @Test
    void uploadRolesMatchTodaysCallers() {
        StorageController.requireMayUpload(StorageCategory.REPAYMENT_PROOF, "ADMIN");
        StorageController.requireMayUpload(StorageCategory.REPAYMENT_PROOF, "ACCOUNTANT");
        StorageController.requireMayUpload(StorageCategory.EXPENSE_RECEIPT, "ADMIN");
        StorageController.requireMayUpload(StorageCategory.PAYMENT_SETTINGS, "ADMIN");
        StorageController.requireMayUpload(StorageCategory.LEAD_IMPORT, "DSA");
        StorageController.requireMayUpload(StorageCategory.LEAD_IMPORT, "TELECALLER");

        assertThatThrownBy(() -> StorageController.requireMayUpload(StorageCategory.REPAYMENT_PROOF, "DSA"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageController.requireMayUpload(StorageCategory.LEAD_IMPORT, "BORROWER"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageController.requireMayUpload(StorageCategory.EXPENSE_RECEIPT, "ACCOUNTANT"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> StorageController.requireMayUpload(StorageCategory.KYC_DOCUMENT, "BORROWER"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void staffMayDownloadALeadImportFile() {
        as("TELECALLER");
        when(storage.presignDownload("leads/import/abc-list.csv")).thenReturn("https://s3/get");

        var res = controller.presignDownload("leads/import/abc-list.csv");

        assertThat(res.url()).isEqualTo("https://s3/get");
    }

    @Test
    void downloadRefusesAnyOtherKey() {
        as("ADMIN");
        assertThatThrownBy(() -> controller.presignDownload("applications/7/aadhaar_card_front/1.jpg"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_KEY");
        assertThatThrownBy(() -> controller.presignDownload("leads/import/../applications/7/pan.jpg"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void downloadRefusesBorrowersAndDsas() {
        as("BORROWER");
        assertThatThrownBy(() -> controller.presignDownload("leads/import/abc-list.csv"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
        as("DSA");
        assertThatThrownBy(() -> controller.presignDownload("leads/import/abc-list.csv"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }
}
