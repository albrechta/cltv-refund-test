package com.coinblesk.cltvrefund;

import static org.bitcoinj.script.ScriptOpCodes.OP_CHECKLOCKTIMEVERIFY;
import static org.bitcoinj.script.ScriptOpCodes.OP_CHECKSIG;
import static org.bitcoinj.script.ScriptOpCodes.OP_CHECKSIGVERIFY;
import static org.bitcoinj.script.ScriptOpCodes.OP_DROP;
import static org.bitcoinj.script.ScriptOpCodes.OP_ELSE;
import static org.bitcoinj.script.ScriptOpCodes.OP_ENDIF;
import static org.bitcoinj.script.ScriptOpCodes.OP_IF;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.utils.BriefLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

public class CLTVRefund {
	private static final Logger LOG = LoggerFactory.getLogger(CLTVRefund.class);
	
	private static final String sendBackToAddress = "mmtFy7wWbzFktUmc6JiudYprEu4bxR8Vun";

	private NetworkParameters params;
    private WalletAppKit appKit;
    private Wallet wallet;
    
    private ECKey userKey, serviceKey;
    
    private Address address;
    private Script redeemScript;
    
    private long expiryTime = 0;
    private boolean spendAfterExpiry;
    

	public CLTVRefund(NetworkParameters params, boolean spendAfterExpiry) {
		BriefLogFormatter.init();
		this.params = params;
		this.spendAfterExpiry = spendAfterExpiry;
	}

	public void run() {
		LOG.info("Start wallet app kit and sync with network.");
		initWallet();

		createKeys();
		
		// create redeem script with time lock in the future.
		expiryTime = wallet.getLastBlockSeenHeight() + 3;
		redeemScript = createTimeLockedContract(serviceKey, userKey, expiryTime);
		LOG.info("Redeem script (lock time {}): {}", expiryTime, redeemScript);
		LOG.info("Redeem script (hex): {}", Utils.HEX.encode(redeemScript.getProgram()));
		
		/*
		 * For P2SH transactions, the scripts have the following form:
		 * Pubkey script: OP_HASH160 <Hash160(redeemScript)> OP_EQUAL
		 * Signature script: <sig> [sig] [sig...] <redeemScript>
		 * 
		 * See: https://bitcoin.org/en/developer-guide#standard-transactions
		 */ 
		// creates OP_HASH160 <Hash160(redeemScript)> OP_EQUAL
		Script pubKeyScript = ScriptBuilder.createP2SHOutputScript(redeemScript);
		LOG.info("pubKeyScript: {}", pubKeyScript);
		
		// derive address from script (hash of redeemScript is extracted from pubKeyScript)
		address = Address.fromP2SHScript(params, pubKeyScript);
		
		// alternative address computation directly from hash of script: 
		// address = Address.fromP2SHHash(params, Utils.sha256hash160(redeemScript.getProgram()));
		LOG.info("Address derived from redeem script: {}", address.toBase58());
		
		wallet.importKey(userKey);
		wallet.addWatchedAddress(address);
		wallet.addCoinsReceivedEventListener(new CoinsReceivedListener());
		
		LOG.info("\n===\n\tPay some coins to the address {}\n===", address);
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				super.run();
				CLTVRefund.this.stop();
			}
		});
		appKit.awaitTerminated();
	}
	
	public void stop() {
		LOG.info("Stopping ...");
		appKit.stopAsync();
	}

	protected void broadcastTx(Transaction txSend) {
		try {
			LOG.info("Broadcast Tx: {}", txSend.toString());
			
			Wallet.SendRequest request = Wallet.SendRequest.forTx(txSend);
			wallet.commitTx(request.tx);
			appKit.peerGroup().broadcastTransaction(request.tx).broadcast().get();
			
		} catch (InterruptedException | ExecutionException e) {
			LOG.warn("Failed broadcast of Tx: {}", e);
		}
	}

	private void initWallet() {
		appKit = new WalletAppKit(params, new File("."), "cltv_refund_example");
	
		// connect to local bitcoind for regtest
		if(params == RegTestParams.get()) {
			appKit.connectToLocalHost();
		}
	    
		appKit.startAsync();
	    appKit.awaitRunning();
	    wallet = appKit.wallet();
	    wallet.allowSpendingUnconfirmedTransactions();
	}

	private void createKeys() {
		// New keys - not random for simplicity
		userKey = ECKey.fromPrivate(Sha256Hash.hash("user-alice".getBytes()));
		LOG.info("User key - priv: {}, pub: {}", userKey.getPrivateKeyAsHex(), userKey.getPublicKeyAsHex());
		serviceKey = ECKey.fromPrivate(Sha256Hash.hash("service-bob".getBytes()));
		LOG.info("Service key - priv: {}, pub: {}", serviceKey.getPrivateKeyAsHex(), serviceKey.getPublicKeyAsHex());
	}

	/**
	 * Create a redeem script with the following time locked contract:
	 *
	 * IF
	 *   <service pubkey> CHECKSIGVERIFY
	 * ELSE
	 *   <expiry time> CHECKLOCKTIMEVERIFY DROP
	 * ENDIF
	 * <user pubkey> CHECKSIG
	 * 
	 * @param serviceKey
	 * @param userKey
	 * @param expiryTime
	 * @return
	 */
	private Script createTimeLockedContract(ECKey serviceKey, ECKey userKey, long expiryTime) {
		return new ScriptBuilder()
				.op(OP_IF)
				.data(serviceKey.getPubKey()).op(OP_CHECKSIGVERIFY)
				.op(OP_ELSE)
				.number(expiryTime).op(OP_CHECKLOCKTIMEVERIFY).op(OP_DROP)
				.op(OP_ENDIF)
				.data(userKey.getPubKey()).op(OP_CHECKSIG)
				.build();
	}
	
	/**
	 * Constructs a scriptSig for the locked contract. It has the following form:
	 * [sig] [sig..] [0|1] [serialized redeemScript]
	 * 
	 * @param txSignatures
	 * @param redeemScript
	 * @param spendAfterExpiry select branch of redeemScript
	 * @return scriptSig
	 */
	private Script createScriptSig(List<TransactionSignature> txSignatures, Script redeemScript, boolean spendAfterExpiry) {
		ScriptBuilder sb = new ScriptBuilder();
		txSignatures.forEach(txS -> sb.data(txS.encodeToBitcoin()));
		sb.smallNum(spendAfterExpiry ? 0 : 1); // select IF (1, before expiry) or ELSE (0, after expiry) branch of script
		sb.data(redeemScript.getProgram());
		return sb.build();
	}
	
	
	private class CoinsReceivedListener implements WalletCoinsReceivedEventListener {
		
		@Override
		public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
			LOG.info("Coins received! " + tx.toString());
			
			// two possibilities: 
			// - send before the expiry time with two signatures
			// - send after the expiry time with just the signature of the user
			if (spendAfterExpiry) {
				LOG.info("Try spending after expiry time ({}) with one signature.", expiryTime);
				spendFromLockedTx_afterExpiry(tx);
			} else {
				LOG.info("Try spending before expiry time ({}) with two signatures.", expiryTime);
				spendFromLockedTx_beforeExpiry(tx);
			}
		}
		
		/**
		 * Send from the locked address before the lock time. This requires 2 signatures.
		 * @param txReceived
		 */
		private void spendFromLockedTx_beforeExpiry(Transaction txReceived) {
			Transaction txSend = prepareSpendTx(txReceived);
			
			// sign and create scriptSig
			TransactionSignature txSigUser = txSend.calculateSignature(0, userKey, redeemScript, Transaction.SigHash.ALL, false);
			TransactionSignature txSigService = txSend.calculateSignature(0, serviceKey, redeemScript, Transaction.SigHash.ALL, false);
			Script scriptSig = createScriptSig(ImmutableList.of(txSigUser, txSigService), redeemScript, false);
			txSend.getInputs().forEach(txIn -> txIn.setScriptSig(scriptSig));

			broadcastTx(txSend);
		}

		/**
		 * Spend from the locked address after the lock time. The signature of the user is sufficient.
		 * @param txReceived
		 */
		private void spendFromLockedTx_afterExpiry(Transaction txReceived) {
			Transaction txSend = prepareSpendTx(txReceived);
			
			// CLTV requires that seq number is smaller than maxint
			txSend.getInputs().forEach(tIn -> tIn.setSequenceNumber(0));
			
			// lock time of transaction must not be smaller than the lock time of the CLTV script (the initial redeem script)
			txSend.setLockTime(expiryTime);
			
			// sign and create scriptSig
			TransactionSignature txSig = txSend.calculateSignature(0, userKey, redeemScript, Transaction.SigHash.ALL, false);
			Script scriptSig = createScriptSig(ImmutableList.of(txSig), redeemScript, true);
			txSend.getInputs().forEach(txIn -> txIn.setScriptSig(scriptSig));
			
			broadcastTx(txSend);
		}
		
		/**
		 * Prepare a transaction that spends coins sent to the time locked address.
		 * @param txReceived 
		 * @return unsigned transaction
		 */
		private Transaction prepareSpendTx(Transaction txReceived) {
			Transaction txSend = new Transaction(params);
			
			// add all inputs sent to our address
			Coin amountToSend = Coin.ZERO;
			for (TransactionOutput out : txReceived.getOutputs()) {
				Address outPointAddr = out.getAddressFromP2SH(params);
				if (outPointAddr != null && outPointAddr.equals(address)) {
					amountToSend = amountToSend.add(out.getValue());
					TransactionInput txIn = txSend.addInput(out);
					txIn.setSequenceNumber(0);
				}
			}
			
			// send coins back to sender.
			Address addressTo = Address.fromBase58(params, sendBackToAddress);
			amountToSend = amountToSend.minus(Coin.MILLICOIN); // some fee
			txSend.addOutput(amountToSend, addressTo);
			
			return txSend;
		}

	}
	
}
