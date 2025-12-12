/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.libcore.timezone.telephonylookup;

import static com.android.libcore.timezone.telephonylookup.TelephonyLookupProtoFileSupport.parseTelephonyLookupTextFile;
import static com.android.libcore.timezone.telephonylookup.TelephonyLookupXmlFile.MobileCountryOverride;

import com.android.libcore.timezone.telephonylookup.proto.TelephonyLookupProtoFile;
import com.android.libcore.timezone.util.Errors;
import com.android.libcore.timezone.util.Errors.HaltExecutionException;

import com.ibm.icu.util.ULocale;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLStreamException;

/**
 * Generates the telephonylookup.xml file using the information from telephonylookup.txt.
 *
 * <p>See {@link #main(String[])} for commandline information.
 */
public final class TelephonyLookupGenerator {

    private final String telephonyLookupProtoFile;
    private final String outputFile;

    /**
     * Executes the generator.
     *
     * <p>Positional arguments: 1: The telephonylookup.txt proto file 2: the file to generate
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println(
                    "usage: java "
                            + TelephonyLookupGenerator.class.getName()
                            + " <input proto file> <output xml file>");
            System.exit(0);
        }
        boolean success = new TelephonyLookupGenerator(args[0], args[1]).execute();
        System.exit(success ? 0 : 1);
    }

    TelephonyLookupGenerator(String telephonyLookupProtoFile, String outputFile) {
        this.telephonyLookupProtoFile = telephonyLookupProtoFile;
        this.outputFile = outputFile;
    }

    boolean execute() throws IOException {
        Errors errors = new Errors();
        try {
            // Parse the countryzones input file.
            TelephonyLookupProtoFile.TelephonyLookup telephonyLookupIn;
            try {
                telephonyLookupIn = parseTelephonyLookupTextFile(telephonyLookupProtoFile);
            } catch (ParseException e) {
                throw errors.addFatalAndHalt("Unable to parse " + telephonyLookupProtoFile, e);
            }

            List<TelephonyLookupProtoFile.MobileCountry> mobileCountriesIn =
                    telephonyLookupIn.getMobileCountriesList();

            validateMobileCountries(mobileCountriesIn, errors);
            errors.throwIfError("One or more validation errors encountered");

            List<TelephonyLookupProtoFile.Network> networksInMobileCountries =
                    extractNetworks(mobileCountriesIn);

            List<TelephonyLookupProtoFile.Network> networksIn =
                    telephonyLookupIn.getNetworksList().stream().sorted(
                            Comparator.comparing(TelephonyLookupProtoFile.Network::getMcc)
                                    .thenComparing(TelephonyLookupProtoFile.Network::getMnc))
                    .collect(Collectors.toList());

            validateNetworks(networksIn, errors);
            errors.throwIfError("One or more validation errors encountered");

            compareNetworkLists(networksInMobileCountries, networksIn, errors);
            errors.throwIfError("Network list comparison failed");

            TelephonyLookupXmlFile.TelephonyLookup telephonyLookupOut =
                    createOutputTelephonyLookup(networksIn, mobileCountriesIn);
            logInfo("Writing " + outputFile);
            try {
                TelephonyLookupXmlFile.write(telephonyLookupOut, outputFile);
            } catch (XMLStreamException e) {
                throw errors.addFatalAndHalt("Unable to write output file", e);
            }
        } catch (HaltExecutionException e) {
            e.printStackTrace();
            logError("Stopping due to fatal error: " + e.getMessage());
        } finally {
            // Report all warnings / errors
            if (!errors.isEmpty()) {
                logInfo("Issues:\n" + errors.asString());
            }
        }
        return !errors.hasError();
    }

    private static void compareNetworkLists(
            List<TelephonyLookupProtoFile.Network> derivedNetworks,
            List<TelephonyLookupProtoFile.Network> deprecatedNetworks,
            Errors errors) {
        errors.pushScope("compareNetworkLists");
        try {
            Set<TelephonyLookupProtoFile.Network> derivedSet = new HashSet<>(derivedNetworks);
            Set<TelephonyLookupProtoFile.Network> deprecatedSet = new HashSet<>(deprecatedNetworks);

            if (!derivedSet.equals(deprecatedSet)) {
                errors.addError(
                        "Mismatch between networks derived from mobile_country overrides and the"
                                + " deprecated 'networks' field.");

                Set<TelephonyLookupProtoFile.Network> inDerivedOnly = new HashSet<>(derivedSet);
                inDerivedOnly.removeAll(deprecatedSet);
                if (!inDerivedOnly.isEmpty()) {
                    errors.addError(
                            "Networks present in overrides but NOT in deprecated 'networks' field: "
                                    + inDerivedOnly.stream()
                                            .map(TelephonyLookupGenerator::formatNetwork)
                                            .collect(Collectors.joining(", ")));
                }

                Set<TelephonyLookupProtoFile.Network> inDeprecatedOnly =
                        new HashSet<>(deprecatedSet);
                inDeprecatedOnly.removeAll(derivedSet);
                if (!inDeprecatedOnly.isEmpty()) {
                    errors.addError(
                            "Networks present in deprecated 'networks' field but NOT in overrides: "
                                    + inDeprecatedOnly.stream()
                                            .map(TelephonyLookupGenerator::formatNetwork)
                                            .collect(Collectors.joining(", ")));
                }
            }
        } finally {
            errors.popScope();
        }
    }

    private static String formatNetwork(TelephonyLookupProtoFile.Network network) {
        return String.format(
                "{mcc=%s, mnc=%s, country=%s}",
                network.getMcc(), network.getMnc(), network.getCountryIsoCode());
    }

    private static void validateNetworks(
            List<TelephonyLookupProtoFile.Network> networksIn, Errors errors) {
        errors.pushScope("validateNetworks");
        try {
            Set<String> knownIsoCountries = getLowerCaseCountryIsoCodes();
            Set<String> mccMncSet = new HashSet<>();
            for (TelephonyLookupProtoFile.Network networkIn : networksIn) {
                String mcc = networkIn.getMcc();
                if (mcc.length() != 3 || !isAsciiNumeric(mcc)) {
                    errors.addError("mcc=" + mcc + " must have 3 decimal digits");
                }

                String mnc = networkIn.getMnc();
                if (!(mnc.length() == 2 || mnc.length() == 3) || !isAsciiNumeric(mnc)) {
                    errors.addError("mnc=" + mnc + " must have 2 or 3 decimal digits");
                }

                String mccMnc = "" + mcc + mnc;
                if (!mccMncSet.add(mccMnc)) {
                    errors.addError("Duplicate entry for mcc=" + mcc + ", mnc=" + mnc);
                }

                String countryIsoCode = networkIn.getCountryIsoCode();
                String countryIsoCodeLower = countryIsoCode.toLowerCase(Locale.ROOT);
                if (!countryIsoCodeLower.equals(countryIsoCode)) {
                    errors.addError("Country code not lower case: " + countryIsoCode);
                }

                if (!knownIsoCountries.contains(countryIsoCodeLower)) {
                    errors.addError("Country code not known: " + countryIsoCode);
                }
            }
        } finally {
            errors.popScope();
        }
    }

    private static void validateMobileCountries(
            List<TelephonyLookupProtoFile.MobileCountry> mobileCountriesIn, Errors errors) {
        errors.pushScope("validateMobileCountries");
        try {
            Set<String> knownIsoCountries = getLowerCaseCountryIsoCodes();
            Set<String> mccSet = new HashSet<>();

            if (mobileCountriesIn.isEmpty()) {
                errors.addError("No mobile countries found");
            }

            for (TelephonyLookupProtoFile.MobileCountry mobileCountryIn : mobileCountriesIn) {
                String mcc = mobileCountryIn.getMcc();
                if (mcc.length() != 3 || !isAsciiNumeric(mcc)) {
                    errors.addError("mcc=" + mcc + " must have 3 decimal digits");
                }

                if (!mccSet.add(mcc)) {
                    errors.addError("Duplicate entry for mcc=" + mcc);
                }

                if (mobileCountryIn.getCountryIsoCodesList().isEmpty()) {
                    errors.addError("Missing countries for mcc=" + mcc);
                }

                for (String countryIsoCode : mobileCountryIn.getCountryIsoCodesList()) {
                    String countryIsoCodeLower = countryIsoCode.toLowerCase(Locale.ROOT);
                    if (!countryIsoCodeLower.equals(countryIsoCode)) {
                        errors.addError("Country code not lower case: " + countryIsoCode);
                    }

                    if (!knownIsoCountries.contains(countryIsoCodeLower)) {
                        errors.addError("Country code not known: " + countryIsoCode);
                    }
                }

                List<TelephonyLookupProtoFile.Override> overrides =
                        mobileCountryIn.getOverridesList();
                validateOverrides(
                        overrides,
                        mcc,
                        knownIsoCountries,
                        mobileCountryIn.getCountryIsoCodesList(),
                        errors);
            }
        } finally {
            errors.popScope();
        }
    }

    private static void validateOverrides(
            List<TelephonyLookupProtoFile.Override> overrides,
            String mcc,
            Set<String> knownIsoCountries,
            List<String> mobileCountryIsoCodes,
            Errors errors) {
        errors.pushScope("validateOverrides");
        try {
            Set<String> overrideMncSet = new HashSet<>();
            for (var override : overrides) {
                if (!override.hasMnc()) {
                    errors.addError("Override for mcc=" + mcc + " is missing mnc");
                } else {
                    String mnc = override.getMnc();
                    if (mnc.length() < 2 || mnc.length() > 3 || !isAsciiNumeric(mnc)) {
                        errors.addError(
                                "Override for mcc="
                                        + mcc
                                        + " has mnc="
                                        + mnc
                                        + ": mnc must have 2 or 3 decimal digits");
                    }
                    if (!overrideMncSet.add(mnc)) {
                        errors.addError("Override for mcc=" + mcc + " has duplicate mnc=" + mnc);
                    }
                    if (override.getCountryIsoCodesList().isEmpty()) {
                        errors.addError(
                                "Override for mcc="
                                        + mcc
                                        + " with mnc="
                                        + mnc
                                        + " missing countryIsoCodes");
                    } else {
                        Set<String> countryIsoCodes = new HashSet<>();
                        for (String countryIsoCode : override.getCountryIsoCodesList()) {
                            String countryIsoCodeLower = countryIsoCode.toLowerCase(Locale.ROOT);
                            if (!countryIsoCodeLower.equals(countryIsoCode)) {
                                errors.addError("Country code not lower case: " + countryIsoCode);
                            }

                            if (!knownIsoCountries.contains(countryIsoCodeLower)) {
                                errors.addError("Country code not known: " + countryIsoCode);
                            }

                            if (!countryIsoCodes.add(countryIsoCodeLower)) {
                                errors.addError(
                                        "Country code "
                                                + countryIsoCodeLower
                                                + " is already defined in the override for mcc="
                                                + mcc
                                                + " with mnc="
                                                + mnc);
                            }
                        }
                        if (countryIsoCodes.equals(new HashSet<>(mobileCountryIsoCodes))) {
                            errors.addError(
                                    "Override for mcc="
                                            + mcc
                                            + " with mnc="
                                            + mnc
                                            + " contains all the same countryIsoCodes as the mobile"
                                            + " country");
                        }
                    }
                }
            }
        } finally {
            errors.popScope();
        }
    }

    private static boolean isAsciiNumeric(String string) {
        for (int i = 0; i < string.length(); i++) {
            char character = string.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static Set<String> getLowerCaseCountryIsoCodes() {
        // Use ICU4J's knowledge of ISO codes because we keep that up to date.
        List<String> knownIsoCountryCodes = Arrays.asList(ULocale.getISOCountries());
        knownIsoCountryCodes =
                knownIsoCountryCodes.stream()
                        .map(x -> x.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toList());
        return new HashSet<>(knownIsoCountryCodes);
    }

    private static TelephonyLookupXmlFile.TelephonyLookup createOutputTelephonyLookup(
            List<TelephonyLookupProtoFile.Network> networksIn,
            List<TelephonyLookupProtoFile.MobileCountry> mobileCountriesIn) {
        // Networks
        List<TelephonyLookupXmlFile.Network> networksOut = new ArrayList<>();
        for (TelephonyLookupProtoFile.Network networkIn : networksIn) {
            String mcc = networkIn.getMcc();
            String mnc = networkIn.getMnc();
            String countryIsoCode = networkIn.getCountryIsoCode();
            TelephonyLookupXmlFile.Network networkOut =
                    new TelephonyLookupXmlFile.Network(mcc, mnc, countryIsoCode);
            networksOut.add(networkOut);
        }

        // Mobile Countries
        List<TelephonyLookupXmlFile.MobileCountry> mobileCountriesOut = new ArrayList<>();
        for (TelephonyLookupProtoFile.MobileCountry mobileCountryIn : mobileCountriesIn) {
            List<MobileCountryOverride> overrideListOut = new ArrayList<>();
            for (TelephonyLookupProtoFile.Override override : mobileCountryIn.getOverridesList()) {
                overrideListOut.add(
                        new MobileCountryOverride(
                                override.getMnc(), override.getCountryIsoCodesList()));
            }
            TelephonyLookupXmlFile.MobileCountry mobileCountryOut =
                    new TelephonyLookupXmlFile.MobileCountry(
                            mobileCountryIn.getMcc(),
                            mobileCountryIn.getCountryIsoCodesList(),
                            overrideListOut);
            mobileCountriesOut.add(mobileCountryOut);
        }

        return new TelephonyLookupXmlFile.TelephonyLookup(networksOut, mobileCountriesOut);
    }

    /**
     * Recreates a list of Network protos from the MobileCountry protos, specifically using the
     * override information, for backwards compatibility.
     *
     * @param mobileCountriesIn List of MobileCountry messages.
     * @return List of Network messages.
     */
    private static List<TelephonyLookupProtoFile.Network> extractNetworks(
            List<TelephonyLookupProtoFile.MobileCountry> mobileCountriesIn) {

        List<TelephonyLookupProtoFile.Network> networks = new ArrayList<>();

        for (TelephonyLookupProtoFile.MobileCountry mobileCountry : mobileCountriesIn) {
            String mcc = mobileCountry.getMcc();

            for (TelephonyLookupProtoFile.Override override : mobileCountry.getOverridesList()) {
                String mnc = override.getMnc();

                // The <networks> section is a legacy format that only supports a single country
                // per MCC+MNC. Overrides with multiple country codes are not present in this
                // section because arbitrarily picking one country would be inaccurate.
                if (override.getCountryIsoCodesList().size() > 1) {
                    continue;
                }

                // Overrides with a single country are expected to be present in both lists.
                String primaryIsoCode = override.getCountryIsoCodesList().getFirst();

                TelephonyLookupProtoFile.Network network =
                        TelephonyLookupProtoFile.Network.newBuilder()
                                .setMcc(mcc)
                                .setMnc(mnc)
                                .setCountryIsoCode(primaryIsoCode)
                                .build();
                networks.add(network);
            }
        }
        return networks;
    }

    private static void logError(String msg) {
        System.err.println("E: " + msg);
    }

    private static void logError(String s, Throwable e) {
        logError(s);
        e.printStackTrace(System.err);
    }

    private static void logInfo(String msg) {
        System.err.println("I: " + msg);
    }
}
