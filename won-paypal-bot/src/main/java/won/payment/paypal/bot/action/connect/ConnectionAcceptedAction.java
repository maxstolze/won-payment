package won.payment.paypal.bot.action.connect;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RSS;

import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandResultEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.wonmessage.OpenFromOtherNeedEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.payment.paypal.bot.event.analyze.ConversationAnalyzationCommandEvent;
import won.payment.paypal.bot.impl.PaypalBotContextWrapper;
import won.payment.paypal.bot.model.PaymentBridge;
import won.payment.paypal.bot.model.PaymentStatus;
import won.payment.paypal.bot.util.InformationExtractor;
import won.payment.paypal.bot.util.WonPayRdfUtils;
import won.payment.paypal.service.impl.PaypalPaymentService;
import won.protocol.agreement.AgreementProtocolState;
import won.protocol.model.Connection;
import won.protocol.util.RdfUtils;
import won.protocol.util.WonRdfUtils;
import won.protocol.vocabulary.WONMOD;
import won.protocol.vocabulary.WONPAY;

/**
 * When the counterpart has accepted the connection, this action will be invoked.
 * It changes the sate of the bridge and generates the payment and sends the link
 * to the buyer.
 * 
 * @author schokobaer
 *
 */
public class ConnectionAcceptedAction extends BaseEventBotAction {

	public ConnectionAcceptedAction(EventListenerContext eventListenerContext) {
		super(eventListenerContext);
	}

	@Override
	protected void doRun(Event event, EventListener executingListener) throws Exception {
		
		if (event instanceof OpenFromOtherNeedEvent) {
			EventListenerContext ctx = getEventListenerContext();
			Connection con = ((OpenFromOtherNeedEvent) event).getCon();
			PaymentBridge bridge = PaypalBotContextWrapper.instance(ctx).getOpenBridge(con.getNeedURI());
			
			if (bridge.getMerchantConnection() != null &&
					con.getConnectionURI().equals(bridge.getMerchantConnection().getConnectionURI())) {
				logger.info("merchant accepted the connection");
				bridge.setStatus(PaymentStatus.BUILDING);
				PaypalBotContextWrapper.instance(ctx).putOpenBridge(con.getNeedURI(), bridge);
				ctx.getEventBus().publish(new ConversationAnalyzationCommandEvent(con));
			} else if (bridge.getStatus() == PaymentStatus.PP_ACCEPTED) {
				logger.info("buyer accepted the connection");
				proposePayModelToBuyer(con);
			} else {
				logger.error("OpenFromOtherNeedEvent from not registered connection URI {}", con.toString());
			}
		}
		
	}
	
	/**
	 * @context Buyer.
	 * @param con
	 */
	private void proposePayModelToBuyer(Connection con) {
		EventListenerContext ctx = getEventListenerContext();
		PaymentBridge bridge = PaypalBotContextWrapper.instance(ctx).getOpenBridge(con.getNeedURI());
		bridge.setStatus(PaymentStatus.BUYER_OPENED);
		PaypalBotContextWrapper.instance(ctx).putOpenBridge(con.getNeedURI(), bridge);
		
		// Get paymodel out of merchants agreement protokoll
		AgreementProtocolState merchantAgreementProtocolState = AgreementProtocolState.of(bridge.getMerchantConnection().getConnectionURI(),
				getEventListenerContext().getLinkedDataSource());

		Model conversation = merchantAgreementProtocolState.getConversationDataset().getUnionModel();
		String paymodelUri = WonPayRdfUtils.getPaymentModelUri(bridge.getMerchantConnection());
		
		Model paymodel = conversation.listStatements(new ResourceImpl(paymodelUri), null, (RDFNode)null).toModel();
		
		Double amount = InformationExtractor.getAmount(paymodel);
		String currency = InformationExtractor.getCurrency(paymodel);
		String receiver = InformationExtractor.getReceiver(paymodel);
		String secret = InformationExtractor.getSecret(paymodel);
		
		String paymentText = "Amount: " + currency + " " + amount + "\nReceiver: " + receiver; 
		paymodel = WonRdfUtils.MessageUtils.addMessage(paymodel, paymentText); // TODO: Add the amount, currency, etc. ...

		// Remove unnecesry statements (counterpart)
		paymodel.removeAll(null, WONPAY.HAS_NEED_COUNTERPART, null);

		// Publish paymodel with proposal
		final ConnectionMessageCommandEvent connectionMessageCommandEvent = new ConnectionMessageCommandEvent(con,
				paymodel);
		ctx.getEventBus().subscribe(ConnectionMessageCommandResultEvent.class, new ActionOnFirstEventListener(ctx,
				new CommandResultFilter(connectionMessageCommandEvent), new BaseEventBotAction(ctx) {
					@Override
					protected void doRun(Event event, EventListener executingListener) throws Exception {
						ConnectionMessageCommandResultEvent connectionMessageCommandResultEvent = (ConnectionMessageCommandResultEvent) event;
						if (connectionMessageCommandResultEvent.isSuccess()) {
							Model agreementMessage = WonRdfUtils.MessageUtils.processingMessage(
									"Accept the paymet to receive the PayPal link to execute it.");
							WonRdfUtils.MessageUtils.addProposes(agreementMessage,
									((ConnectionMessageCommandSuccessEvent) connectionMessageCommandResultEvent)
											.getWonMessage().getMessageURI());
							ctx.getEventBus().publish(new ConnectionMessageCommandEvent(con, agreementMessage));
						} else {
							logger.error("FAILURERESPONSEEVENT FOR PROPOSAL PAYLOAD");
						}
					}
				}));

		ctx.getEventBus().publish(connectionMessageCommandEvent);
		
	}

}
