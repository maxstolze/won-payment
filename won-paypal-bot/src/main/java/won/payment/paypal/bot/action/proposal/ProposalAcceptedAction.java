package won.payment.paypal.bot.action.proposal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.vocabulary.DC;
import org.apache.jena.vocabulary.RSS;
import org.apache.velocity.runtime.directive.Literal;

import com.paypal.svcs.types.ap.PayRequest;
import com.paypal.svcs.types.ap.Receiver;
import com.paypal.svcs.types.ap.ReceiverList;

import won.bot.framework.eventbot.EventListenerContext;
import won.bot.framework.eventbot.action.BaseEventBotAction;
import won.bot.framework.eventbot.event.Event;
import won.bot.framework.eventbot.event.impl.analyzation.agreement.ProposalAcceptedEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandEvent;
import won.bot.framework.eventbot.event.impl.command.connect.ConnectCommandSuccessEvent;
import won.bot.framework.eventbot.event.impl.command.connectionmessage.ConnectionMessageCommandEvent;
import won.bot.framework.eventbot.filter.impl.CommandResultFilter;
import won.bot.framework.eventbot.listener.EventListener;
import won.bot.framework.eventbot.listener.impl.ActionOnFirstEventListener;
import won.payment.paypal.bot.event.ComplexConnectCommandEvent;
import won.payment.paypal.bot.impl.PaypalBotContextWrapper;
import won.payment.paypal.bot.model.PaymentBridge;
import won.payment.paypal.bot.model.PaymentStatus;
import won.payment.paypal.bot.util.InformationExtractor;
import won.payment.paypal.bot.util.WonPaymentRdfUtils;
import won.payment.paypal.service.impl.PaypalPaymentService;
import won.protocol.message.WonMessage;
import won.protocol.message.WonMessageBuilder;
import won.protocol.model.Connection;
import won.protocol.service.WonNodeInformationService;
import won.protocol.util.RdfUtils;
import won.protocol.util.WonRdfUtils;
import won.protocol.vocabulary.WONPAY;

/**
 * After a proposal got accepted this Action will be invoked.
 * 
 * @author schokobaer
 *
 */
public class ProposalAcceptedAction extends BaseEventBotAction {

	public ProposalAcceptedAction(EventListenerContext eventListenerContext) {
		super(eventListenerContext);
	}

	@Override
	protected void doRun(Event event, EventListener executingListener) throws Exception {
		
		EventListenerContext ctx = getEventListenerContext();
		
		if (event instanceof ProposalAcceptedEvent) {
			ProposalAcceptedEvent proposalAcceptedEvent = (ProposalAcceptedEvent) event;
            Connection con = proposalAcceptedEvent.getCon();
            PaymentBridge bridge = PaypalBotContextWrapper.paymentBridge(ctx, con);
            
            if (bridge.getStatus() == PaymentStatus.GOALSATISFIED) {
            	// Merchant has accepted the payment proposal
            	connectToBuyer(proposalAcceptedEvent);
            } else if (bridge.getStatus() == PaymentStatus.PROPOSED) {
            	// Buyer has accepted the payment
            	bridge.setStatus(PaymentStatus.ACCEPTED);
            	PaypalBotContextWrapper.instance(ctx).putOpenBridge(con.getNeedURI(), bridge);
            	generatePayment(proposalAcceptedEvent);
            } else {
            	return;
            }
                        
            
		}

	}
	
	private void makeTextMsg(String msg, Connection con) {
		if (con == null) {
			return;
		}
		Model model = WonRdfUtils.MessageUtils.processingMessage(msg);
		getEventListenerContext().getEventBus().publish(new ConnectionMessageCommandEvent(con, model));
	}
	
	private void connectToBuyer(ProposalAcceptedEvent event) throws URISyntaxException {
		EventListenerContext ctx = getEventListenerContext();
		Connection con = event.getCon();
        PaymentBridge bridge = PaypalBotContextWrapper.paymentBridge(ctx, con);
        
		Model payload = event.getPayload();
		Double amount = InformationExtractor.getAmount(payload);
        String currency = InformationExtractor.getCurrency(payload);
        String receiver = InformationExtractor.getReceiver(payload);
        String secret = InformationExtractor.getSecret(payload);
		URI counterPartNeedUri = new URI(InformationExtractor.getCounterpart(payload));
		payload.setNsPrefix("pay", WONPAY.BASE_URI);
		
		String openingMsg = "Payment request\nAmount: " + currency + " " + amount + "\nReceiver: " + receiver + "\nSecret: " + secret;
		        
        //logger.info("merchant accepted proposal for " + payload.listStatements().next().getResource().getLocalName());
        
		// TODO: Test to send payload instead of simple text message
        //ConnectCommandEvent connectCommandEvent = new ConnectCommandEvent(con.getNeedURI(), counterPartNeedUri, openingMsg);
		ComplexConnectCommandEvent connectCommandEvent = new ComplexConnectCommandEvent(con.getNeedURI(), counterPartNeedUri, openingMsg, payload);
        ctx.getEventBus().subscribe(ConnectCommandSuccessEvent.class, new ActionOnFirstEventListener(ctx, new CommandResultFilter(connectCommandEvent), new BaseEventBotAction(ctx) {

			@Override
			protected void doRun(Event event, EventListener executingListener) throws Exception {
				
				if (event instanceof ConnectCommandSuccessEvent) {
					logger.info("created successfully a connection to buyer");
					ConnectCommandSuccessEvent connectSuccessEvent = (ConnectCommandSuccessEvent)event;
					URI needUri = connectSuccessEvent.getNeedURI();
					Connection buyerCon = connectSuccessEvent.getCon();
					bridge.setBuyerConnection(buyerCon);
					bridge.setStatus(PaymentStatus.PUBLISHED);
					PaypalBotContextWrapper.instance(ctx).putOpenBridge(needUri, bridge);
					makeTextMsg("Payment published to buyer.", bridge.getMerchantConnection());
				}
				
			}
        	
        }));
        
        ctx.getEventBus().publish(connectCommandEvent);
        //sendComplexConnectMessageToBuyer(connectCommandEvent);
	}
	
	private void generatePayment(ProposalAcceptedEvent event) {
		EventListenerContext ctx = getEventListenerContext();
		Connection con = event.getCon();
        PaymentBridge bridge = PaypalBotContextWrapper.paymentBridge(ctx, con);
        
        Model payload = event.getPayload();
		Double amount = InformationExtractor.getAmount(payload);
        String currency = InformationExtractor.getCurrency(payload);
        String receiver = InformationExtractor.getReceiver(payload);
        String feePayer = InformationExtractor.getFeePayer(payload);
        String expirationTime = InformationExtractor.getExpirationTime(payload);
        String invoiceDetails = InformationExtractor.getInvoiceDetails(payload);
        String invoiceId = InformationExtractor.getInvoiceId(payload);
        Double tax = InformationExtractor.getTax(payload);
		// TODO: Tax
        
        try {
        	PayRequest pay = new PayRequest();
    		// Set receiver
    		Receiver rec = new Receiver();
    		rec.setAmount(amount);
    		rec.setEmail(receiver);
    		pay.setReceiverList(new ReceiverList(Collections.singletonList(rec)));
    		pay.setCurrencyCode(currency);
    		
    		if (feePayer != null) {
    			feePayer = feePayer.equals(WONPAY.FEE_PAYER_SENDER.getURI()) ? "SENDER" : "RECEIVER";
    			pay.setFeesPayer(feePayer);
    		}
    		if (expirationTime != null) {
    			pay.setPayKeyDuration(expirationTime);
    		}
    		if (invoiceDetails != null) {
    			pay.setMemo(invoiceDetails);
    		}
    		if (invoiceId != null) {
    			rec.setInvoiceId(invoiceId);
    		}   		
    		

        	PaypalPaymentService paypalService = PaypalBotContextWrapper.instance(ctx).getPaypalService();
			String payKey = paypalService.create(pay);
			String url = paypalService.getPaymentUrl(payKey);

			bridge.setStatus(PaymentStatus.GENERATED);
			bridge.setPayKey(payKey);
			PaypalBotContextWrapper.instance(ctx).putOpenBridge(con.getNeedURI(), bridge);
			
			// Print pay link to buyer; Tell merchant everything is fine
			Model respBuyer = WonRdfUtils.MessageUtils.processingMessage("Click on this link for executing the payment: \n" + url);
			RdfUtils.findOrCreateBaseResource(respBuyer).addProperty(RSS.link, new ResourceImpl(url));
			Model respMerch = WonRdfUtils.MessageUtils.processingMessage("Payment was generated. Waiting for the buyer to execute ...");
			
			ctx.getEventBus().publish(new ConnectionMessageCommandEvent(con, respBuyer));
			ctx.getEventBus().publish(new ConnectionMessageCommandEvent(bridge.getMerchantConnection(), respMerch));
			logger.info("Paypal Payment generated with payKey={}", payKey);
		} catch (Exception e) {
			logger.warn("Paypal payment could not be generated.", e);
		}
	}
	
	
	
	private void sendComplexConnectMessageToBuyer(ComplexConnectCommandEvent connectCommandEvent) {
		
		// TODO: Set a good message;
		// Only show the important payment details (no counterpart for example)
		// Find out if the messeage was publish successfully
		
		WonNodeInformationService wonNodeInformationService =
                getEventListenerContext().getWonNodeInformationService();

        Dataset localNeedRDF =
                getEventListenerContext().getLinkedDataSource().getDataForResource(connectCommandEvent.getNeedURI());
        Dataset remoteNeedRDF =
                getEventListenerContext().getLinkedDataSource().getDataForResource(connectCommandEvent.getRemoteNeedURI());

        URI localWonNode = WonRdfUtils.NeedUtils.getWonNodeURIFromNeed(localNeedRDF, connectCommandEvent.getNeedURI());
        URI remoteWonNode = WonRdfUtils.NeedUtils.getWonNodeURIFromNeed(remoteNeedRDF, connectCommandEvent.getRemoteNeedURI());


        WonMessage message = 
                WonMessageBuilder.setMessagePropertiesForConnect(
                        wonNodeInformationService.generateEventURI(
                                localWonNode),
                        connectCommandEvent.getLocalFacet(),
                        connectCommandEvent.getNeedURI(),
                        localWonNode,
                        connectCommandEvent.getRemoteFacet(),
                        connectCommandEvent.getRemoteNeedURI(),
                        remoteWonNode,
                        connectCommandEvent.getWelcomeMessage())
                		.addContent(connectCommandEvent.getPayload())
                        .build();
		
		getEventListenerContext().getWonMessageSender().sendWonMessage(message);
	}

}