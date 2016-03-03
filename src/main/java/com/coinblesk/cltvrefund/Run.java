package com.coinblesk.cltvrefund;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;

public class Run {

	private static NetworkParameters params;

	public static void main(String[] args) {
		if (args.length == 0) {
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

		new CLTVRefund(params).run();
	}

	private static void exitWrongParam() {
		System.out.println("Usage: specify network with parameter [ -regtest | -testnet | -mainnet ]");
		System.exit(-1);
	}
}
