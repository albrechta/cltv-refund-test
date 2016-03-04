package com.coinblesk.cltvrefund;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;

public class Run {

	private static NetworkParameters params;

	public static void main(String[] args) {
		if (args.length != 2) {
			exitWrongParam();
		}

		String network = args[0];
		if (network.equals("-regtest")) {
			params = RegTestParams.get();
		} else if (network.equals("-testnet")) {
			params = TestNet3Params.get();
		} else if (network.equals("-mainnet")) {
			params = MainNetParams.get();
		} else {
			exitWrongParam();
		}
		
		String spendMode = args[1];
		boolean spendAfterExpiry = false;
		if (spendMode.equals("-beforeLockTime")) {
			spendAfterExpiry = false;
		} else if (spendMode.equals("-afterLockTime")) {
			spendAfterExpiry = true;
		} else {
			exitWrongParam();
		}

		new CLTVRefund(params, spendAfterExpiry).run();
	}

	private static void exitWrongParam() {
		System.out.println("Usage: [network parameter] [spend mode]");
	    System.out.println("\tNetwork parameter: [ -regtest | -testnet | -mainnet ]");
	    System.out.println("\tSpend mode: [-beforeLockTime | -afterLockTime]");
		System.exit(-1);
	}
}
