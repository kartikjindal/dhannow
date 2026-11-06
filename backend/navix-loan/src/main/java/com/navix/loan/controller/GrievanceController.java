package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.ComplaintService;
import com.navix.loan.service.ComplaintService.BorrowerComplaintView;
import com.navix.loan.service.ComplaintService.ComplaintView;
import com.navix.loan.service.ComplaintService.FileRequest;
import com.navix.loan.service.ComplaintService.StaffComplaintView;
import com.navix.loan.service.ComplaintService.StaffFileRequest;
import com.navix.loan.service.LoanDisputeService;
import com.navix.loan.service.LoanDisputeService.DisputeView;
import com.navix.loan.service.LoanDisputeService.HoldEventView;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN Phase 7: complaints, disputes and the SLA holiday calendar. Roles are checked in the services:
 * borrowers file and read their own tickets; SUPPORT/ADMIN work the queue (COMPLIANCE reads it); ADMIN, SUPPORT
 * and COLLECTION_HEAD raise and release disputes; ADMIN keeps the holiday calendar.
 */
@RestController
@RequiredArgsConstructor
public class GrievanceController {

    private final ComplaintService complaints;
    private final LoanDisputeService disputes;

    public record NoteRequest(String note, Long version) {
    }

    public record MoveRequest(String status, String note, Long version) {
    }

    public record AssignRequest(Long staffId) {
    }

    public record DisputeRequest(String reason, Long complaintId) {
    }

    public record HolidayRequest(String name) {
    }

    // ---- borrower ----

    @PostMapping("/api/complaints")
    public ApiResponse<BorrowerComplaintView> file(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody FileRequest request) {
        return ApiResponse.ok(complaints.file(request, idempotencyKey));
    }

    @GetMapping("/api/complaints")
    public ApiResponse<List<BorrowerComplaintView>> mine() {
        return ApiResponse.ok(complaints.mine());
    }

    @GetMapping("/api/complaints/{id}")
    public ApiResponse<BorrowerComplaintView> mineOne(@PathVariable Long id) {
        return ApiResponse.ok(complaints.mineOne(id));
    }

    // ---- staff queue ----

    @GetMapping("/api/staff/complaints")
    public ApiResponse<List<ComplaintView>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(required = false) Long assignedTo,
                                                 @RequestParam(required = false) Long customerId,
                                                 @RequestParam(required = false) Boolean breached) {
        return ApiResponse.ok(complaints.list(status, category, assignedTo, customerId, breached));
    }

    @PostMapping("/api/staff/complaints")
    public ApiResponse<ComplaintView> fileFor(@RequestBody StaffFileRequest request) {
        return ApiResponse.ok(complaints.fileFor(request));
    }

    @GetMapping("/api/staff/complaints/{id}")
    public ApiResponse<StaffComplaintView> get(@PathVariable Long id) {
        return ApiResponse.ok(complaints.get(id));
    }

    @PostMapping("/api/staff/complaints/{id}/acknowledge")
    public ApiResponse<ComplaintView> acknowledge(@PathVariable Long id, @RequestBody(required = false) NoteRequest r) {
        return ApiResponse.ok(complaints.acknowledge(id, r == null ? null : r.note(), r == null ? null : r.version()));
    }

    @PostMapping("/api/staff/complaints/{id}/assign")
    public ApiResponse<ComplaintView> assign(@PathVariable Long id, @RequestBody AssignRequest r) {
        return ApiResponse.ok(complaints.assign(id, r.staffId()));
    }

    @PostMapping("/api/staff/complaints/{id}/status")
    public ApiResponse<ComplaintView> move(@PathVariable Long id, @RequestBody MoveRequest r) {
        return ApiResponse.ok(complaints.move(id, ComplaintService.parseStatus(r.status()), r.note(), r.version()));
    }

    @PostMapping("/api/staff/complaints/{id}/resolve")
    public ApiResponse<ComplaintView> resolve(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(complaints.resolve(id, r.note(), r.version()));
    }

    @PostMapping("/api/staff/complaints/{id}/escalate")
    public ApiResponse<ComplaintView> escalate(@PathVariable Long id, @RequestBody NoteRequest r) {
        return ApiResponse.ok(complaints.escalate(id, r.note(), r.version()));
    }

    // ---- disputes ----

    @PostMapping("/api/loans/{loanId}/dispute")
    public ApiResponse<DisputeView> raise(@PathVariable Long loanId, @RequestBody DisputeRequest r) {
        return ApiResponse.ok(disputes.raise(loanId, r.reason(), r.complaintId()));
    }

    @PostMapping("/api/loans/{loanId}/dispute/release")
    public ApiResponse<DisputeView> release(@PathVariable Long loanId, @RequestBody NoteRequest r) {
        return ApiResponse.ok(disputes.release(loanId, r.note()));
    }

    @GetMapping("/api/loans/{loanId}/holds")
    public ApiResponse<List<HoldEventView>> holds(@PathVariable Long loanId) {
        return ApiResponse.ok(disputes.history(loanId));
    }

    // ---- SLA holiday calendar (CC-64) ----

    @GetMapping("/api/admin/holidays")
    public ApiResponse<List<ComplaintService.Holiday>> holidays() {
        return ApiResponse.ok(complaints.holidays());
    }

    @PutMapping("/api/admin/holidays/{day}")
    public ApiResponse<List<ComplaintService.Holiday>> addHoliday(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestBody HolidayRequest r) {
        return ApiResponse.ok(complaints.addHoliday(day, r.name()));
    }

    @DeleteMapping("/api/admin/holidays/{day}")
    public ApiResponse<List<ComplaintService.Holiday>> removeHoliday(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return ApiResponse.ok(complaints.removeHoliday(day));
    }
}
