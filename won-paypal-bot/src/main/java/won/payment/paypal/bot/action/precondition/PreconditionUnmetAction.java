package won.payment.paypal.bot.action.precondition;

import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.topbraid.shacl.vocabulary.SH;

import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.BaseAtomAndConnectionSpecificEvent;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.analyzation.precondition.PreconditionEvent;
import won.bot.framework.eventbot.event.impl.analyzation.precondition.PreconditionUnmetEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.listener.EventListener;
import won.payment.paypal.bot.impl.PaypalBotContextWrapper;
import won.payment.paypal.bot.model.PaymentContext;
import won.payment.paypal.bot.model.PaymentStatus;
import won.protocol.agreement.AgreementProtocolState;
import won.protocol.model.Connection;
import won.protocol.util.WonRdfUtils;
import won.protocol.vocabulary.WONPAY;
import won.utils.goals.GoalInstantiationResult;
import won.utils.shacl.ValidationResultWrapper;

/**
 * When the analyzation throws a precondition met event. Then a new proposal is
 * made, if there is no old one which has exactly the same content.
 * 
 * @author schokobaer
 */
public class PreconditionUnmetAction extends BaseEventBotAction {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    public PreconditionUnmetAction(EventListenerContext eventListenerContext) {
        super(eventListenerContext);
    }

    @Override
    protected void doRun(Event event, EventListener executingListener) throws Exception {
        EventListenerContext ctx = getEventListenerContext();
        if (ctx.getBotContextWrapper() instanceof PaypalBotContextWrapper && event instanceof PreconditionUnmetEvent) {
            Connection con = ((BaseAtomAndConnectionSpecificEvent) event).getCon();
            PaymentContext payCtx = ((PaypalBotContextWrapper) ctx.getBotContextWrapper())
                    .getPaymentContext(con.getAtomURI());
            if (payCtx.getStatus() != PaymentStatus.BUILDING) {
                return;
            }
            logger.info("Precondition unmet");
            retractProposal(con);
            GoalInstantiationResult preconditionEventPayload = ((PreconditionEvent) event).getPayload();
            // FIXME: bot does not communicate what information is missing.
            Model messageModel = WonRdfUtils.MessageUtils
                    .processingMessage("To generate a payment link, send a message with a Payment detail.");
            // RDF output with SHACL report:
            String respondWith = "SHACL report: Payment not possible yet, missing necessary Values: \n";
            for (ValidationResultWrapper validationResultWrapper : preconditionEventPayload.getShaclReportWrapper()
                    .getValidationResults()) {
                if (validationResultWrapper.getResultPath() != null || validationResultWrapper.getFocusNode() != null) {
                    String path = validationResultWrapper.getResultPath().getLocalName();
                    if (path != null && !path.isEmpty()) {
                        respondWith += path + ": ";
                    } else {
                        path = validationResultWrapper.getFocusNode().getLocalName();
                        respondWith += !path.isEmpty() ? path + ": " : "";
                    }
                }
                respondWith += validationResultWrapper.getResultMessage() + " \n";
                Resource report = messageModel.createResource();
                if (validationResultWrapper.getFocusNode() != null) {
                    report.addProperty(SH.focusNode, validationResultWrapper.getFocusNode());
                }
                if (validationResultWrapper.getDetail() != null) {
                    report.addProperty(SH.detail, validationResultWrapper.getDetail());
                }
                if (validationResultWrapper.getResultPath() != null) {
                    report.addProperty(SH.resultPath, validationResultWrapper.getResultPath());
                }
                if (validationResultWrapper.getResultSeverity() != null) {
                    report.addProperty(SH.resultSeverity, validationResultWrapper.getResultSeverity());
                }
                if (validationResultWrapper.getValue() != null) {
                    report.addProperty(SH.value, validationResultWrapper.getValue());
                }
                if (validationResultWrapper.getResultMessage() != null) {
                    report.addProperty(SH.resultMessage, validationResultWrapper.getResultMessage());
                }
                WonRdfUtils.MessageUtils.addToMessage(messageModel, SH.result, report);
            }
            logger.info(respondWith);
            getEventListenerContext().getEventBus().publish(new ConnectionMessageCommandEvent(con, messageModel));
        }
    }

    /**
     * Retracts the latest Proposal (if available) together with the payment
     * summary.
     * 
     * @param con
     */
    private void retractProposal(Connection con) {
        AgreementProtocolState agreementProtocolState = AgreementProtocolState.of(con.getConnectionURI(),
                getEventListenerContext().getLinkedDataSource());
        URI proposalUri = agreementProtocolState.getLatestPendingProposal();
        if (proposalUri == null) {
            return;
        }
        Model proposalModel = agreementProtocolState.getPendingProposal(proposalUri);
        StmtIterator itr = proposalModel.listStatements(null, RDF.type, WONPAY.PAYMENT_SUMMARY);
        if (!itr.hasNext()) {
            return;
        }
        Resource paymentSummary = itr.next().getSubject();
        try {
            Model retractResponse = WonRdfUtils.MessageUtils.retractsMessage(new URI(paymentSummary.getURI()),
                    proposalUri);
            getEventListenerContext().getEventBus().publish(new ConnectionMessageCommandEvent(con, retractResponse));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }
}
