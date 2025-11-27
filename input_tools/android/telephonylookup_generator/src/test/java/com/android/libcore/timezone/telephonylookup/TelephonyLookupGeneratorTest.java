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

import static com.android.libcore.timezone.testing.TestUtils.assertContains;
import static com.android.libcore.timezone.testing.TestUtils.createFile;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.libcore.timezone.telephonylookup.proto.TelephonyLookupProtoFile.MobileCountry;
import com.android.libcore.timezone.telephonylookup.proto.TelephonyLookupProtoFile.Network;
import com.android.libcore.timezone.telephonylookup.proto.TelephonyLookupProtoFile.Override;
import com.android.libcore.timezone.telephonylookup.proto.TelephonyLookupProtoFile.TelephonyLookup;
import com.android.libcore.timezone.testing.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class TelephonyLookupGeneratorTest {

    private Path tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("TelephonyLookupGeneratorTest");
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.deleteDir(tempDir);
    }

    @Test
    public void invalidTelephonyLookupFile() throws Exception {
        String telephonyLookupFile = createFile(tempDir, "THIS IS NOT A VALID FILE");
        String outputFile = Files.createTempFile(tempDir, "out", null /* suffix */).toString();

        TelephonyLookupGenerator telephonyLookupGenerator =
                new TelephonyLookupGenerator(telephonyLookupFile, outputFile);
        assertFalse(telephonyLookupGenerator.execute());

        assertFileIsEmpty(outputFile);
    }

    @Test
    public void mobileCountries_upperCaseCountryIsoCodeIsRejected() throws Exception {
        MobileCountry mobileCountry = createMobileCountry("123", List.of("GB"), List.of());
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_unknownCountryIsoCodeIsRejected() throws Exception {
        MobileCountry mobileCountry = createMobileCountry("123", List.of("gb", "zx"), List.of());
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_badMccIsRejected_nonNumeric() throws Exception {
        MobileCountry mobileCountry = createMobileCountry("XXX", List.of("gb"), List.of());
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_badMccIsRejected_tooShort() throws Exception {
        MobileCountry mobileCountry = createMobileCountry("12", List.of("gb"), List.of());
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_badMccIsRejected_tooLong() throws Exception {
        MobileCountry mobileCountry = createMobileCountry("1234", List.of("gb"), List.of());
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_canNotOverrideWithTheSameCountry() throws Exception {
        Override override = createOverride("456", List.of("gb"));
        MobileCountry mobileCountry = createMobileCountry("1234", List.of("gb"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void mobileCountries_duplicateMccComboIsRejected() throws Exception {
        MobileCountry mobileCountry1 = createMobileCountry("123", List.of("gb"), List.of());
        MobileCountry mobileCountry2 = createMobileCountry("123", List.of("gb"), List.of());
        checkGenerationFails(
                createTelephonyLookup(List.of(), List.of(mobileCountry1, mobileCountry2)));
    }

    @Test
    public void overrides_upperCaseCountryIsoCodeIsRejected() throws Exception {
        Network network = createNetwork("123", "456", "GB");
        Override override = createOverride("456", List.of("GB"));
        MobileCountry mobileCountry = createMobileCountry("123", List.of("gb"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void overrides_unknownCountryIsoCodeIsRejected() throws Exception {
        Network network = createNetwork("123", "456", "zx");
        Override override = createOverride("456", List.of("zx"));
        MobileCountry mobileCountry = createMobileCountry("123", List.of("zx"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void overrides_badMccIsRejected_nonNumeric() throws Exception {
        Network network = createNetwork("XXX", "456", "gb");
        Override override = createOverride("456", List.of("gb"));
        MobileCountry mobileCountry = createMobileCountry("XXX", List.of("gb"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void overrides_badMccIsRejected_tooShort() throws Exception {
        Network network = createNetwork("12", "456", "gb");
        Override override = createOverride("456", List.of("gb"));
        MobileCountry mobileCountry = createMobileCountry("12", List.of("gb"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void overrides_badMccIsRejected_tooLong() throws Exception {
        Network network = createNetwork("1234", "567", "gb");
        Override override = createOverride("567", List.of("gb"));
        MobileCountry mobileCountry = createMobileCountry("1234", List.of("gb"), List.of(override));
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void overrides_cannotOverrideWithTheSameMcc() throws Exception {
        Network network1 = createNetwork("123", "456", "gb");
        Network network2 = createNetwork("123", "456", "us");
        Override override1 = createOverride("456", List.of("gb"));
        Override override2 = createOverride("456", List.of("us"));
        MobileCountry mobileCountry =
                createMobileCountry("123", List.of("ky"), List.of(override1, override2));
        checkGenerationFails(
                createTelephonyLookup(List.of(network1, network2), List.of(mobileCountry)));
    }

    @Test
    public void overrides_cannotOverrideWithTheSameCountrySet() throws Exception {
        Network network1 = createNetwork("123", "456", "us");
        Override override1 = createOverride("456", List.of("us", "gb"));
        MobileCountry mobileCountry =
                createMobileCountry("123", List.of("us", "gb"), List.of(override1));
        checkGenerationFails(createTelephonyLookup(List.of(network1), List.of(mobileCountry)));
    }

    @Test
    public void networkComparison_mismatch_networksHasExtra() throws Exception {
        Network extraNetwork = createNetwork("999", "111", "us");
        MobileCountry mobileCountry = createMobileCountry("123", List.of("gb"), List.of());
        // networksInDeprecated has an extra, mobileCountries is empty
        checkGenerationFails(createTelephonyLookup(List.of(extraNetwork), List.of(mobileCountry)));
    }

    @Test
    public void networkComparison_mismatch_overridesHaveExtra() throws Exception {
        Override override = createOverride("456", List.of("jm"));
        MobileCountry mobileCountry = createMobileCountry("123", List.of("gb"), List.of(override));
        // networksInDeprecated is empty, but overrides will generate a network
        checkGenerationFails(createTelephonyLookup(List.of(), List.of(mobileCountry)));
    }

    @Test
    public void createNetworksFromMobileCountriesOverrides_networkProtoUsesSecondCountry_fails()
            throws Exception {
        Network network = createNetwork("310", "100", "pr");
        Override multiCountryOverride = createOverride("100", List.of("us", "pr", "gu"));
        MobileCountry mobileCountry =
                createMobileCountry("310", List.of("ky"), List.of(multiCountryOverride));

        // The network proto generated from the override will use the first country in the list,
        // which is us, while network from networks section uses the second country in the list.
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void createNetworkProtoUsesSecondCountry_fails() throws Exception {
        Network network = createNetwork("310", "100", "pr");
        Override multiCountryOverride = createOverride("100", List.of("us", "pr", "gu"));
        MobileCountry mobileCountry =
                createMobileCountry("310", List.of("ky"), List.of(multiCountryOverride));

        // The network proto generated from the override will use the first country in the list,
        // which is us, while network from networks section uses the second country in the list.
        checkGenerationFails(createTelephonyLookup(List.of(network), List.of(mobileCountry)));
    }

    @Test
    public void validDataCreatesFile() throws Exception {
        Network network1 = createNetwork("123", "456", "jm");
        Network network2 = createNetwork("456", "456", "jm");
        Network network3 = createNetwork("456", "56", "gb");
        Override override1 = createOverride("456", List.of("jm"));
        Override override2 = createOverride("56", List.of("gb", "ky"));
        MobileCountry mobileCountry1 =
                createMobileCountry("123", List.of("gb"), List.of(override1));
        MobileCountry mobileCountry2 =
                createMobileCountry("456", List.of("us", "fr"), List.of(override1, override2));
        TelephonyLookup telephonyLookupProto =
                createTelephonyLookup(
                        List.of(network1, network2, network3),
                        List.of(mobileCountry1, mobileCountry2));

        String telephonyLookupXml = generateTelephonyLookupXml(telephonyLookupProto);
        assertContains(
                trimAndLinearize(telephonyLookupXml),
                "<network mcc=\"123\" mnc=\"456\" country=\"jm\"/>",
                "<network mcc=\"456\" mnc=\"56\" country=\"gb\"/>",
                "<network mcc=\"456\" mnc=\"456\" country=\"jm\"/>",
                """
                <mobile_country mcc="123"><country>gb</country>\
                <override mnc="456"><country>jm</country></override></mobile_country>\
                """,
                """
                <mobile_country mcc="456" default="us"><country>us</country>\
                <country>fr</country><override mnc="456"><country>jm</country></override>\
                <override mnc="56"><country>gb</country><country>ky</country></override>\
                </mobile_country>\
                """);
    }

    private void checkGenerationFails(TelephonyLookup telephonyLookup) throws Exception {
        String telephonyLookupFile = createTelephonyLookupFile(telephonyLookup);
        String outputFile = Files.createTempFile(tempDir, "out", null /* suffix */).toString();

        TelephonyLookupGenerator telephonyLookupGenerator =
                new TelephonyLookupGenerator(telephonyLookupFile, outputFile);
        assertFalse(telephonyLookupGenerator.execute());

        assertFileIsEmpty(outputFile);
    }

    private String createTelephonyLookupFile(TelephonyLookup telephonyLookup) throws Exception {
        return TestUtils.createFile(tempDir, telephonyLookup.toString());
    }

    private String generateTelephonyLookupXml(TelephonyLookup telephonyLookup) throws Exception {
        String telephonyLookupFile = createTelephonyLookupFile(telephonyLookup);

        String outputFile = Files.createTempFile(tempDir, "out", null /* suffix */).toString();

        TelephonyLookupGenerator telephonyLookupGenerator =
                new TelephonyLookupGenerator(telephonyLookupFile, outputFile);
        assertTrue(telephonyLookupGenerator.execute());

        Path outputFilePath = Paths.get(outputFile);
        assertTrue(Files.exists(outputFilePath));

        return readFileToString(outputFilePath);
    }

    private static String readFileToString(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Network createNetwork(String mcc, String mnc, String isoCountryCode) {
        return Network.newBuilder()
                .setMcc(mcc)
                .setMnc(mnc)
                .setCountryIsoCode(isoCountryCode)
                .build();
    }

    private static Override createOverride(String mnc, List<String> isoCountryCodes) {
        return Override.newBuilder().setMnc(mnc).addAllCountryIsoCodes(isoCountryCodes).build();
    }

    private static MobileCountry createMobileCountry(
            String mcc, List<String> countryIsoCodes, List<Override> overrides) {
        return MobileCountry.newBuilder()
                .setMcc(mcc)
                .addAllCountryIsoCodes(countryIsoCodes)
                .addAllOverrides(overrides)
                .build();
    }

    private static TelephonyLookup createTelephonyLookup(
            List<Network> networks, List<MobileCountry> mobileCountries) {
        return TelephonyLookup.newBuilder()
                .addAllNetworks(networks)
                .addAllMobileCountries(mobileCountries)
                .build();
    }

    private static void assertFileIsEmpty(String outputFile) throws IOException {
        Path outputFilePath = Paths.get(outputFile);
        assertEquals(0, Files.size(outputFilePath));
    }

    private static String trimAndLinearize(String input) {
        return input.lines().map(String::trim).collect(Collectors.joining());
    }
}
