package nxt.http;

import nxt.*;
import nxt.crypto.Crypto;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

import static nxt.http.JSONResponses.*;

abstract class CreateTransaction extends APIServlet.APIRequestHandler {
/**/
    private static final String[] commonParameters = new String[] {"secretPhrase", "publicKey", "feeNQT",
            "deadline", "referencedTransactionFullHash", "broadcast"};

    private static String[] addCommonParameters(String[] parameters) {
        Logger.logMessage("创建一个交易+CreateTransaction ");
        String[] result = Arrays.copyOf(parameters, parameters.length + commonParameters.length);
        System.arraycopy(commonParameters, 0, result, parameters.length, commonParameters.length);
        return result;
    }

    CreateTransaction(String... parameters) {
        super(addCommonParameters(parameters));
    }

    final JSONStreamAware createTransaction(HttpServletRequest req, Account senderAccount, Attachment attachment)
        throws NxtException {
        return createTransaction(req, senderAccount, Genesis.CREATOR_ID, 0, attachment);
    }

    final JSONStreamAware createTransaction(HttpServletRequest req, Account senderAccount, Long recipientId,
                                            long amountNQT, Attachment attachment)
            throws NxtException {
        String deadlineValue = req.getParameter("deadline");
        String referencedTransactionFullHash = Convert.emptyToNull(req.getParameter("referencedTransactionFullHash"));
        String referencedTransactionId = Convert.emptyToNull(req.getParameter("referencedTransaction"));
        String secretPhrase = Convert.emptyToNull(req.getParameter("secretPhrase"));
        String publicKeyValue = Convert.emptyToNull(req.getParameter("publicKey"));
        boolean broadcast = !"false".equalsIgnoreCase(req.getParameter("broadcast"));

        if (secretPhrase == null && publicKeyValue == null) {
            return MISSING_SECRET_PHRASE;
        } else if (deadlineValue == null) {
            return MISSING_DEADLINE;
        }

        short deadline;
        try {
            deadline = Short.parseShort(deadlineValue);
            if (deadline < 1 || deadline > 1440) {
                return INCORRECT_DEADLINE;
            }
        } catch (NumberFormatException e) {
            return INCORRECT_DEADLINE;
        }

        long feeNQT = ParameterParser.getFeeNQT(req);
        if (feeNQT < minimumFeeNQT()) {
            return INCORRECT_FEE;
        }

        try {
            if (Convert.safeAdd(amountNQT, feeNQT) > senderAccount.getUnconfirmedBalanceNQT()) {
                return NOT_ENOUGH_FUNDS;
            }
        } catch (ArithmeticException e) {
            return NOT_ENOUGH_FUNDS;
        }

        if (referencedTransactionId != null) {
            return INCORRECT_REFERENCED_TRANSACTION;
        }

        JSONObject response = new JSONObject();

        // shouldn't try to get publicKey from senderAccount as it may have not been set yet
        byte[] publicKey = secretPhrase != null ? Crypto.getPublicKey(secretPhrase) : Convert.parseHexString(publicKeyValue);

        try {
            Logger.logMessage("HTTP-------create  transaction.........");
            Transaction transaction = attachment == null ?
                    Nxt.getTransactionProcessor().newTransaction(deadline, publicKey, recipientId,
                            amountNQT, feeNQT, referencedTransactionFullHash)
                    :
                    Nxt.getTransactionProcessor().newTransaction(deadline, publicKey, recipientId,
                            amountNQT, feeNQT, referencedTransactionFullHash, attachment);

            if (secretPhrase != null) {
                Logger.logMessage("对交易进行签名........");
                transaction.sign(secretPhrase);
                response.put("transaction", transaction.getStringId());
                response.put("fullHash", transaction.getFullHash());
                response.put("transactionBytes", Convert.toHexString(transaction.getBytes()));
                response.put("signatureHash", Convert.toHexString(Crypto.sha256().digest(transaction.getSignature())));
                if (broadcast) {
                    Logger.logMessage("将交易广播出去......");
                    Nxt.getTransactionProcessor().broadcast(transaction);
                    response.put("broadcasted", true);
                } else {
                    response.put("broadcasted", false);
                }
            } else {
                response.put("broadcasted", false);
            }
            response.put("unsignedTransactionBytes", Convert.toHexString(transaction.getUnsignedBytes()));

        } catch (TransactionType.NotYetEnabledException e) {
            return FEATURE_NOT_AVAILABLE;
        } catch (NxtException.ValidationException e) {
            response.put("error", e.getMessage());
        }
        return response;

    }

    @Override
    final boolean requirePost() {
        return true;
    }

    long minimumFeeNQT() {
        return Constants.ONE_NXT;
    }

}
