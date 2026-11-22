package app.carecontinuo.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Adapter contracts for services configured by the institution. */
public final class IntegrationPorts {
    private IntegrationPorts() { }

    public interface SchedulingFeed {
        Batch<ExternalSlot> readSlots(String departmentRef, OffsetDateTime from, OffsetDateTime to, String cursor);
    }

    public interface ClinicalFeed {
        Batch<Discharge> readDischarges(String departmentRef, String cursor);
        Batch<InvestigationResult> readResults(String departmentRef, String cursor);
    }

    public interface NotificationGateway {
        Delivery submit(ExternalMessage request);
        Delivery status(String providerMessageId);
    }

    public interface DocumentRecognition {
        List<RecognizedLine> recognize(byte[] file, String mediaType);
    }

    public interface PaymentGateway {
        PaymentResult create(PaymentRequest request);
        PaymentResult status(String orderRef);
        PaymentResult cancel(String orderRef, String idempotencyKey);
    }

    public interface VideoGateway {
        VideoSession open(String appointmentRef, OffsetDateTime startsAt, OffsetDateTime endsAt, String idempotencyKey);
        void close(String sessionRef, String idempotencyKey);
    }

    public interface InstitutionExchange {
        void send(ExchangeRecord reference);
        ExchangeReceipt status(String exchangeRef);
    }

    public interface VisitPreparationAssistant {
        String summarize(String submittedSelfReport);
    }

    public interface MedicationReferenceFeed {
        List<MedicationReference> search(String genericName, String dosageForm, String strength);
    }

    public interface HospitalWorkflowFeed {
        Batch<WorkflowChange> readAdmissions(String departmentRef, String cursor);
        Batch<WorkflowChange> readConsultations(String departmentRef, String cursor);
    }

    public interface IdentityLinkVerifier {
        VerifiedLink verify(String subjectRef, String patientRef, String assertionRef);
    }

    public record Batch<T>(List<T> items, String nextCursor) { }
    public record ExternalSlot(String eventId, long revision, String slotRef, String doctorRef, String departmentRef,
                               OffsetDateTime startsAt, OffsetDateTime endsAt, int capacity, boolean stopped) { }
    public record Discharge(String eventId, long revision, String encounterRef, String patientRef,
                            String departmentRef, LocalDate dischargedOn, String protectedDocumentRef) { }
    public record InvestigationResult(String eventId, long revision, String encounterRef, String patientRef,
                                      String reportRef, String protectedDocumentRef, boolean releasedBySource) { }
    public record ExternalMessage(String idempotencyKey, String recipientRef, String templateCode, String actionRef) { }
    public record Delivery(String providerMessageId, DeliveryStatus state, OffsetDateTime observedAt) { }
    public enum DeliveryStatus { ACCEPTED, DELIVERED, FAILED }
    public record RecognizedLine(int page, int line, String text) { }
    public record PaymentRequest(String idempotencyKey, String appointmentRef, String payerRef, String currency,
                                 long minorUnits, String coverageRef) { }
    public record PaymentResult(String orderRef, PaymentState state) { }
    public enum PaymentState { PENDING, AUTHORIZED, PAID, FAILED, CANCELLED, REFUNDED }
    public record VideoSession(String sessionRef) { }
    public record ExchangeRecord(String exchangeRef, String sendingInstitutionRef, String receivingInstitutionRef,
                                 String authorizationRef, String protectedDocumentRef) { }
    public record ExchangeReceipt(String exchangeRef, OffsetDateTime acceptedAt) { }
    public record MedicationReference(String genericName, String dosageForm, String strength, String sourceVersion,
                                      String authoritativeDocumentRef) { }
    public record WorkflowChange(String eventId, long revision, String encounterRef, String patientRef,
                                 String departmentRef, String workflowState, OffsetDateTime occurredAt) { }
    public record VerifiedLink(String subjectRef, String patientRef, OffsetDateTime verifiedAt, OffsetDateTime validUntil) { }
}
