package org.broadinstitute.hellbender.tools.walkers.haplotypecaller;

import com.google.common.collect.Maps;
import htsjdk.samtools.*;
import htsjdk.samtools.util.Locatable;
import htsjdk.variant.variantcontext.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.GATKBaseTest;
import org.broadinstitute.gatk.nativebindings.smithwaterman.SWParameters;
import org.broadinstitute.hellbender.engine.AssemblyRegion;
import org.broadinstitute.hellbender.testutils.VariantContextTestUtils;
import org.broadinstitute.hellbender.utils.BaseUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.*;
import org.broadinstitute.hellbender.utils.haplotype.Event;
import org.broadinstitute.hellbender.utils.haplotype.EventMap;
import org.broadinstitute.hellbender.utils.haplotype.Haplotype;
import org.broadinstitute.hellbender.utils.read.ArtificialReadUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.SAMRecordToGATKReadAdapter;
import org.broadinstitute.hellbender.utils.smithwaterman.SmithWatermanAligner;
import org.broadinstitute.hellbender.utils.smithwaterman.SmithWatermanAlignmentConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.broadinstitute.hellbender.tools.walkers.haplotypecaller.AssemblyBasedCallerUtils.*;

public class AssemblyBasedCallerUtilsUnitTest extends GATKBaseTest {
    private static final SWParameters HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS = SmithWatermanAlignmentConstants.NEW_SW_PARAMETERS;

    final SAMFileHeader header = ArtificialReadUtils.createArtificialSamHeader(1, 1, 100000000);
    final SAMLineParser parser = new SAMLineParser(header);

    // In finalizeRegion(), the base qualities of overlapped read clips pairs are adjusted.
    // Most of read clips are clipped/copy from original reads, and the base qualities of original reads are not affected.
    // However, GATK 4.0.0.x has a bug : in some cases, the clipping procedure directly returns the original reads.
    // So the base qualities of original reads are changed.
    // This test is added to make sure the unexpected behavior is fixed.
    @Test
    public void testfinalizeRegion() {
        SAMFileHeader header;
        header = ArtificialReadUtils.createArtificialSamHeader(1, 1, 100000000);
        SAMReadGroupRecord rg = new SAMReadGroupRecord("tumor");
        rg.setSample("tumor");
        header.addReadGroup(rg);

        Assert.assertEquals(header.getSequenceIndex("1"), 0);
        final AssemblyRegion activeRegion = new AssemblyRegion(new SimpleInterval("1",42596728,42598843), 100, header);
        Assert.assertTrue(activeRegion.isActive());
        Assert.assertFalse(activeRegion.isFinalized());

        SAMLineParser parser = new SAMLineParser(header);
        List<GATKRead> reads = new LinkedList<GATKRead>();
        // NOTE: These reads are mates that overlap one-another without agreement which means they should have modified base qualities after calling finalize()
        //       Read2 has a clean cigar, and thus will not be copied by the clipping code before being fed to the overlapping bases code. This test asserts that its still copied.
        SAMRecord orgRead0 = parser.parseLine("HWI-ST807:461:C2P0JACXX:4:2204:18080:5857\t83\t1\t42596803\t39\t1S95M5S\t=\t42596891\t-7\tGAATCATCATCAAATGGAATCTAATGGAATCATTGAACAGAATTGAATGGAATCGTCATCGAATGAATTGAATGCAATCATCGAATGGTCTCGAATAGAAT\tDAAAEDCFCCGEEDDBEDDDGCCDEDECDDFDCEECCFEECDCEDBCDBDBCC>DCECC>DBCDDBCBDDBCDDEBCCECC>DBCDBDBGC?FCCBDB>>?\tRG:Z:tumor");
        SAMRecord orgRead1 = parser.parseLine("HWI-ST807:461:C2P0JACXX:4:2204:18080:5857\t163\t1\t42596891\t39\t101M\t=\t42596803\t7\tCTCGAATGGAATCATTTTCTACTGGAAAGGAATGGAATCATCGCATAGAATCGAATGGAATTAACATGGAATGGAATCGAATGTAATCATCATCAAATGGA\t>@>:ABCDECCCEDCBBBDDBDDEBCCBEBBCBEBCBCDDCD>DECBGCDCF>CCCFCDDCBABDEDFCDCDFFDDDG?DDEGDDFDHFEGDDGECB@BAA\tRG:Z:tumor");

        // use a copy of the original reads so the original reads are not touched
        reads.add(new SAMRecordToGATKReadAdapter(orgRead0.deepCopy()));
        reads.add(new SAMRecordToGATKReadAdapter(orgRead1.deepCopy()));

        // add reads into active region and call finalizeRegion
        activeRegion.addAll(reads);
        SampleList sampleList = SampleList.singletonSampleList("tumor");
        Byte minbq = 9;
        // NOTE: this test MUST be run with correctOverlappingBaseQualities enabled otherwise this test can succeed even with unsafe code
        AssemblyBasedCallerUtils.finalizeRegion(activeRegion, false, false, minbq, header, sampleList, true, false, false, false);

        // make sure that the original reads are not changed due to finalizeRegion()
        Assert.assertTrue(reads.get(0).convertToSAMRecord(header).equals(orgRead0));
        Assert.assertTrue(reads.get(1).convertToSAMRecord(header).equals(orgRead1));
    }

    // ------------------------------------------------------------------------
    //
    //  Test annotation of reads for bamout
    //
    // ------------------------------------------------------------------------
    @DataProvider(name = "testAnnotateReadLikelihoodsWithRegionsDataProvider")
    public Object[][] testAnnotateReadLikelihoodsWithRegionsDataProvider() {

        final String hap1String = "ACGTGGCGTTGCACTTCAGATCGATCGGATCGATCGGCTAGTCGTCGCACTTCGCTAGGCTAG";
        final String contig = "1";
        final int start = 13763;
        final SimpleInterval loc = new SimpleInterval(contig, start, start + hap1String.length());
        final SimpleInterval loc2 = new SimpleInterval(contig, loc.getStart() + 4, loc.getEnd() - 4);
        List<Integer> snpIndices = Arrays.asList(null, 12, 23);
        List<Character> snpReplacements = Arrays.asList(null, 'G', 'C');
        List<Integer> delIndices = Arrays.asList(null, 27);
        List<Integer> delLenghts = Arrays.asList(null, 4);
        List<Integer> insIndices = Arrays.asList(null, 34);
        List<String> insStrings = Arrays.asList(null, "GGCTGGATCGAG");

        List<String> haplotypeStrings = new ArrayList<String>();

        for (int iSnp = 0; iSnp < snpIndices.size(); iSnp++) {
            //create set of haplotype sequences with different combinations of snps, deletions, indels
            Integer snpIndex = snpIndices.get(iSnp);
            Character snpReplacement = snpReplacements.get(iSnp);
            for (int iDel = 0; iDel < delIndices.size(); iDel++) {
                Integer delIndex = delIndices.get(iDel);
                Integer delLength = delLenghts.get(iDel);
                for (int iIns = 0; iIns < insIndices.size(); iIns++) {
                    Integer insIndex = insIndices.get(iIns);
                    String insString = insStrings.get(iIns);
                    String haplotypeString = hap1String;
                    if (snpIndex != null) {
                        haplotypeString = applySNP(haplotypeString, snpIndex, snpReplacement);
                    }
                    if (insIndex != null) {
                        haplotypeString = applyInsertion(haplotypeString, insIndex, insString);
                    }
                    if (delIndex != null) {
                        haplotypeString = applyDeletion(haplotypeString, delIndex, delLength);
                    }
                    haplotypeStrings.add(haplotypeString);
                }
            }
        }
        final List<Haplotype> haplotypesList = haplotypeStrings.stream().map(h -> new Haplotype(h.getBytes(), loc)).collect(Collectors.toList());

        int qNameIndex = 0;
        List<GATKRead> reads = new ArrayList<>();
        Map<GATKRead, Haplotype> readHaplotypeMap = new HashMap<>();
        for (Haplotype haplotype : haplotypesList) {
            //create a bunch of reads for different haplotypes
            final String hapString = haplotype.getBaseString();
            for (int readStart = 0; readStart < hapString.length() - 6; readStart += 3) {
                for (int readEnd = Math.min(hapString.length(), readStart + 20); readEnd < Math.min(hapString.length(), readStart + 40); readEnd += 4) {
                    final String readString = hapString.substring(readStart, readEnd);
                    final String cigar = readString.length() + "M"; //actual cigar is unimportant for this test
                    final String qname = "r" + qNameIndex;
                    qNameIndex++;
                    final SAMRecordToGATKReadAdapter read = buildRead(readString,cigar,start+readStart,qname,contig);
                    reads.add(read);
                    readHaplotypeMap.put(read, haplotype);
                }
            }
        }
        Map<String, List<GATKRead>> sampleReadMap = new HashMap<>();
        sampleReadMap.put("sample1", reads);
        final AlleleList<Haplotype> haplotypes = new IndexedAlleleList<>(haplotypesList);
        final SampleList samples = new IndexedSampleList("sample1");

        final AlleleLikelihoods<GATKRead, Haplotype> readLikelihoods = new AlleleLikelihoods<>(samples, haplotypes, sampleReadMap);
        LikelihoodMatrix<GATKRead, Haplotype> sampleMatrix = readLikelihoods.sampleMatrix(0);
        for (GATKRead read : reads) {
            //set likelihoods, -1.0 for haplotype read assigned to, -8.0 for all other haplotypes
            final int readIndex = sampleMatrix.indexOfEvidence(read);
            for (Haplotype haplotype : haplotypesList) {
                final int haplotypeIndex = sampleMatrix.indexOfAllele(haplotype);
                if (readHaplotypeMap.get(read) == haplotype) {
                    sampleMatrix.set(haplotypeIndex, readIndex, -1.0);
                } else {
                    sampleMatrix.set(haplotypeIndex, readIndex, -8.0);
                }
            }
        }

        return new Object[][]{{readLikelihoods, loc, loc},
                {readLikelihoods, loc, loc2}
        };

    }


    private String applySNP(final String initialSeq, final int iReplace, final char replaceWith) {
        if (initialSeq.length() <= iReplace) {
            return initialSeq;
        }
        String retSeq = initialSeq.substring(0, iReplace) + replaceWith;
        if (initialSeq.length() > iReplace + 1) {
            retSeq += initialSeq.substring(iReplace + 1);
        }
        return retSeq;
    }

    private String applyDeletion(final String initialSeq, final int iDelete, final int lengthDelete) {
        if (initialSeq.length() <= iDelete) {
            return initialSeq;
        }
        String retSeq = initialSeq.substring(0, iDelete);
        if (initialSeq.length() > iDelete + lengthDelete) {
            retSeq += initialSeq.substring(iDelete + lengthDelete);
        }
        return retSeq;
    }

    private String applyInsertion(final String initialSeq, final int iInsert, final String insertionSeq) {
        if (initialSeq.length() <= iInsert) {
            return initialSeq;
        }
        String retSeq = initialSeq.substring(0, iInsert) + insertionSeq;
        if (initialSeq.length() > iInsert + 1) {
            retSeq += initialSeq.substring(iInsert + 1);
        }
        return retSeq;
    }

    private SAMRecordToGATKReadAdapter buildRead(final String seq, final String cigar, final int pos, final String qName,final String rName) {
        final String baseQuals = StringUtils.repeat("<", seq.length());
        final int mapq = 39;
        final String rnext = "=";
        final int pnext = pos + 100;
        final int tlen = 200;
        final int flag = 83;
        final String samLine = qName + "\t" + flag + "\t" + rName + "\t" + pos + "\t" + mapq + "\t" + cigar + "\t" + rnext + "\t" +
                pnext + "\t" + tlen + "\t" + seq + "\t" + baseQuals;
        final SAMRecord samRead = parser.parseLine(samLine);
        final SAMRecordToGATKReadAdapter read = new SAMRecordToGATKReadAdapter(samRead);

        return read;
    }

    @Test(dataProvider = "testAnnotateReadLikelihoodsWithRegionsDataProvider")
    public void testAnnotateReadLikelihoodsWithRegions(AlleleLikelihoods<GATKRead, Haplotype> readLikelihoods, final Locatable loc, final Locatable callableLoc) {
        annotateReadLikelihoodsWithRegions(readLikelihoods, callableLoc);
        for (GATKRead read : readLikelihoods.sampleEvidence(0)) {
            Assert.assertEquals(read.getAttributeAsString(ALIGNMENT_REGION_TAG), loc.toString());
            Assert.assertEquals(read.getAttributeAsString(CALLABLE_REGION_TAG), callableLoc.toString());
        }
    }

    @DataProvider(name = "testAnnotateReadLikelihoodsWithSupportedAllelesDataProvider")
    public Object[][] testAnnotateReadLikelihoodsWithSupportedAllelesDataProvider() {
        final String refString = "ACGTGGCGTTGCACTTCAGATCGATCGGATCGATCGGCTAGTCGTCGCACTTCGCTAGGCTAG";
        final String contig = "1";
        final int start = 13763;
        final SimpleInterval loc = new SimpleInterval(contig, start, start + refString.length());
        final SAMFileHeader header = ArtificialReadUtils.createArtificialSamHeader(1, 1, 100000000);
        SAMLineParser parser = new SAMLineParser(header);

        final Haplotype refHaplotype = new Haplotype(refString.getBytes(), loc);
        final Cigar refCigar = new Cigar(Arrays.asList(new CigarElement(refString.length(), CigarOperator.M)));
        refHaplotype.setCigar(refCigar);

        //add three variants (2 snps and an indel)
        List<VariantContext> vcs = new ArrayList<>();
        vcs.add(new VariantContextBuilder("source", contig, start + 3, start + 3, Arrays.asList(
                Allele.create("T".getBytes(), true), Allele.create("G".getBytes(), false))).make());
        vcs.add(new VariantContextBuilder("source", contig, start + 38, start + 38, Arrays.asList(
                Allele.create("T".getBytes(), true), Allele.create("G".getBytes(), false), Allele.create("C".getBytes(), false))).make());
        vcs.add(new VariantContextBuilder("source", contig, start + 20, start + 23, Arrays.asList(
                Allele.create("TCGA", true), Allele.create("T", false))).make());

        Map<VariantContext, List<GATKRead>> vcReadsMap = new HashMap<>();
        Map<GATKRead, Integer> supportedAlleleMap = new HashMap<>();
        for (VariantContext vc : vcs) {
            vcReadsMap.put(vc, new ArrayList<>());
        }

        int qNameIndex = 0;
        //three true haplotypes.  All alt for first snp and indel, cycle through 3 alleles for second snp
        for (int readStart = loc.getStart(); readStart < loc.getEnd() - 6; readStart += 4) {
            for (int readEnd = Math.min(readStart + 20, loc.getEnd()); readEnd < Math.min(readStart + 50, loc.getEnd()); readEnd += 3) {
                final SimpleInterval readLoc = new SimpleInterval(contig, readStart, readEnd);
                final Haplotype refRead = refHaplotype.trim(readLoc);
                for (int phase = 0; phase < 3; phase++) {
                    Haplotype readHaplotype = applyVariant(vcs.get(0), refRead, 0);
                    if (phase > 0) {
                        readHaplotype = applyVariant(vcs.get(1), readHaplotype, phase - 1);
                    }
                    readHaplotype = applyVariant(vcs.get(2), readHaplotype, 0);
                    final String readString = readHaplotype.getBaseString();
                    final String cigar = readString.length() + "M"; //actual cigar is unimportant for this test
                    final String qname = "r" + qNameIndex;
                    qNameIndex++;
                    final SAMRecordToGATKReadAdapter read = buildRead(readString,cigar,readStart,qname,contig);
                    supportedAlleleMap.put(read, phase);
                    for (final VariantContext vc : vcs) {
                        if (read.getStart() <= vc.getStart() && read.getEnd() >= vc.getEnd()) {
                            vcReadsMap.get(vc).add(read);
                        }
                    }

                }
            }
        }
        List<AlleleLikelihoods<GATKRead, Allele>> readLikelihoodsList = new ArrayList<>();
        List<List<String>> readAttributeListList = new ArrayList<>();
        for (VariantContext vc : vcs) {
            Map<String, List<GATKRead>> sampleReadMap = new HashMap<>();
            sampleReadMap.put("sample1", vcReadsMap.get(vc));
            final SampleList samples = new IndexedSampleList("sample1");
            final AlleleList<Allele> alleles = new IndexedAlleleList<>(vc.getAlleles());
            final AlleleLikelihoods<GATKRead, Allele> readLikelihoods = new AlleleLikelihoods<>(samples, alleles, sampleReadMap);
            LikelihoodMatrix<GATKRead, Allele> sampleMatrix = readLikelihoods.sampleMatrix(0);
            List<String> readAttributeList = new ArrayList<>();
            for (GATKRead read : vcReadsMap.get(vc)) {
                final int readIndex = sampleMatrix.indexOfEvidence(read);
                String attribute = contig + ":" + vc.getStart() + "=";
                if (vc == vcs.get(1)) {
                    attribute += supportedAlleleMap.get(read);
                } else {
                    attribute += "1";
                }
                readAttributeList.add(attribute);
                for (Allele allele : vc.getAlleles()) {
                    final int alleleIndex = sampleMatrix.indexOfAllele(allele);
                    if (vc == vcs.get(1)) {
                        if (vc.getAlleleIndex(allele) == supportedAlleleMap.get(read)) {
                            sampleMatrix.set(alleleIndex, readIndex, -1.0);
                        } else {
                            sampleMatrix.set(alleleIndex, readIndex, -8.0);
                        }
                    } else {
                        if (vc.getAlleleIndex(allele) == 1) {
                            sampleMatrix.set(alleleIndex, readIndex, -1.0);
                        } else {
                            sampleMatrix.set(alleleIndex, readIndex, -8.0);
                        }
                    }
                }
            }
            readLikelihoodsList.add(readLikelihoods);
            readAttributeListList.add(readAttributeList);
        }
        return new Object[][]{{readLikelihoodsList, vcs, readAttributeListList}};
    }

    private Haplotype applyVariant(final VariantContext vc, final Haplotype refHaplotype, final int altIndex) {
        Haplotype retHaplotype = refHaplotype.insertAllele(vc.getReference(), vc.getAlternateAllele(altIndex), vc.getStart());
        if (retHaplotype == null) {
            return refHaplotype;
        }
        retHaplotype.setGenomeLocation(refHaplotype.getGenomeLocation());
        final Cigar cigar = new Cigar(Arrays.asList(new CigarElement(retHaplotype.length(), CigarOperator.M)));
        retHaplotype.setCigar(cigar);
        return retHaplotype;
    }

    @Test(dataProvider = "testAnnotateReadLikelihoodsWithSupportedAllelesDataProvider")
    public void testAnnotateReadLikelihoodsWithSupportedAlleles(List<AlleleLikelihoods<GATKRead, Allele>> readLikelihoodsList, final List<VariantContext> vcs, final List<List<String>> readAttributeListList) {
        for (int i = 0; i < readLikelihoodsList.size(); i++) {
            AlleleLikelihoods<GATKRead, Allele> readLikelihoods = readLikelihoodsList.get(i);
            VariantContext vc = vcs.get(i);
            List<String> readAttributeList = readAttributeListList.get(i);


            List<String> initReadAttributes = new ArrayList<>();
            for (GATKRead read : readLikelihoods.sampleEvidence(0)) {
                initReadAttributes.add(read.getAttributeAsString(SUPPORTED_ALLELES_TAG));
            }
            annotateReadLikelihoodsWithSupportedAlleles(vc, readLikelihoods);
            for (int j = 0; j < readLikelihoods.sampleEvidenceCount(0); j++) {
                GATKRead read = readLikelihoods.sampleEvidence(0).get(j);

                String expectedAttribute = (initReadAttributes.get(j) != null ? initReadAttributes.get(j) + ", " : "") + readAttributeList.get(j);
                Assert.assertEquals(read.getAttributeAsString(SUPPORTED_ALLELES_TAG), expectedAttribute);
            }
        }
    }

    @DataProvider(name = "getVariantContextsFromActiveHaplotypes")
    public Object[][] getVariantContextsFromActiveHaplotypesData() {
        final List<Object[]> tests = new ArrayList<>();

        tests.add(new Object[]{new ArrayList<>(), 1000, new ArrayList<>()});

        final Haplotype snpHaplotype = new Haplotype("ACTGGTCAACTGGTCAACTGGTCAACTGGTCA".getBytes());
        final Event snpEvent = new Event("20", 1000, Allele.create("A", true), Allele.create("G"));
        snpHaplotype.setEventMap(EventMap.of(snpEvent));

        // this one matches the snp haplotype above (to test duplicate removal)
        final Haplotype snpHaplotypeDuplicate = new Haplotype("ACTGGTCAACTGGTCAACTGGTCAACTGGACA".getBytes());
        final Event snpEvent2 = new Event("20", 1000, Allele.create("A", true), Allele.create("G"));
        final Event snpEvent3 = new Event("20", 1020, Allele.create("T", true), Allele.create("A"));
        snpHaplotypeDuplicate.setEventMap(EventMap.of(snpEvent2, snpEvent3));


        final Haplotype deletionHaplotype = new Haplotype("ACTGGTCAGGTCAACTGGTCA".getBytes());
        final Event deletionEvent = new Event("20", 995, Allele.create("ACTGGTCAACT", true), Allele.create("A"));
        deletionHaplotype.setEventMap(EventMap.of(deletionEvent));

        // matches the deletion alleles above but at a different position (to catch an edge case in duplicate removal)
        final Haplotype deletionHaplotypeFalseDuplicate = new Haplotype("ACTGGTCAGGTCAACTGGTCA".getBytes());
        final Event deletionEventFalseDuplicate = new Event("20", 998, Allele.create("ACTGGTCAACT", true), Allele.create("A"));
        deletionHaplotypeFalseDuplicate.setEventMap(EventMap.of(deletionEventFalseDuplicate));

        // doesn't overlap 1000
        final Haplotype deletionHaplotypeNoSpan = new Haplotype("CAACTGGTCAACTGGTCAACTGGTCAACTGGTCAACTGGTCA".getBytes());
        final Event deletionEventNoSpan = new Event("20", 990, Allele.create("GTCAA", true), Allele.create("G"));
        deletionHaplotypeNoSpan.setEventMap(EventMap.of(deletionEventNoSpan));

        tests.add(new Object[]{Arrays.asList(snpHaplotype), 1000, Arrays.asList(snpEvent)});
        tests.add(new Object[]{Arrays.asList(snpHaplotype, snpHaplotypeDuplicate), 1000, Arrays.asList(snpEvent)});
        tests.add(new Object[]{Arrays.asList(deletionHaplotype), 995, Arrays.asList(deletionEvent)});
        tests.add(new Object[]{Arrays.asList(deletionHaplotype), 1000, Arrays.asList(deletionEvent)});
        tests.add(new Object[]{Arrays.asList(deletionHaplotype, deletionHaplotypeNoSpan), 1000, Arrays.asList(deletionEvent)});
        tests.add(new Object[]{Arrays.asList(deletionHaplotype, deletionHaplotypeFalseDuplicate, deletionHaplotypeNoSpan), 1000, Arrays.asList(deletionEvent, deletionEventFalseDuplicate)});

        tests.add(new Object[]{Arrays.asList(deletionHaplotype, snpHaplotype), 1000, Arrays.asList(deletionEvent, snpEvent)});

        final Haplotype sameLocDelHap1 = new Haplotype("AAAAAAAGAAA".getBytes());
        final Event sameLocDelEvent1 = new Event("20", 10093568, Allele.create("GTT", true), Allele.create("G"));
        sameLocDelHap1.setEventMap(EventMap.of(sameLocDelEvent1));

        final Haplotype sameLocDelHap2 = new Haplotype("AAAAAAAGTAAA".getBytes());
        final Event sameLocDelEvent2 = new Event("20", 10093568, Allele.create("GT", true), Allele.create("G"));
        sameLocDelHap2.setEventMap(EventMap.of(sameLocDelEvent2));

        final Haplotype sameLocInsHap1 = new Haplotype("AAAAAAAGTTTAAA".getBytes());
        final Event sameLocInsEvent1 = new Event("20", 10093568, Allele.create("G", true), Allele.create("GT"));
        sameLocInsHap1.setEventMap(EventMap.of(sameLocInsEvent1));

        tests.add(new Object[]{Arrays.asList(sameLocDelHap1, sameLocDelHap2, sameLocInsHap1), 10093568, Arrays.asList(sameLocDelEvent1, sameLocDelEvent2, sameLocInsEvent1)});

        return tests.toArray(new Object[][]{});
    }

    @Test(dataProvider = "getVariantContextsFromActiveHaplotypes")
    public void testGetVariantContextsFromActiveHaplotypes(final List<Haplotype> haplotypes,
                                                           final int loc,
                                                           final List<Event> expectedEventsAtThisLocation) {

        final List<VariantContext> vcsAtThisPosition = getVariantsFromActiveHaplotypes(loc, haplotypes, true);
        Assert.assertEquals(vcsAtThisPosition.size(), expectedEventsAtThisLocation.size());
        for (int i = 0; i < expectedEventsAtThisLocation.size(); i++) {
            VariantContextTestUtils.assertVariantContextsAreEqual(vcsAtThisPosition.get(i), expectedEventsAtThisLocation.get(i).convertToVariantContext("SRC"), new ArrayList<>(), Collections.emptyList());
        }
    }

    @DataProvider(name = "getEventMapper")
    public Object[][] getEventMapperData() {

        final Haplotype refHaplotype = new Haplotype("ACTGGTCAACTAGTCAACTGGTCAACTGGTCA".getBytes());
        refHaplotype.setEventMap(EventMap.of());

        final Haplotype snpHaplotype = new Haplotype("ACTGGTCAACTGGTCAACTGGTCAACTGGTCA".getBytes());
        final Allele refAllele = Allele.create("A", true);
        final List<Allele> snpAlleles = Arrays.asList(refAllele, Allele.create("G"));
        final Event snpEvent = new Event("20", 1000, refAllele, Allele.create("G"));
        snpHaplotype.setEventMap(EventMap.of(snpEvent));

        final Haplotype snpHaplotypeNotPresentInEventsAtThisLoc = new Haplotype("ACTGGTCAACTTGTCAACTGGTCAACTGGTCA".getBytes());
        final Event snpEventNotPresentInEventsAtThisLoc = new Event("20", 1000, refAllele, Allele.create("T"));
        snpHaplotypeNotPresentInEventsAtThisLoc.setEventMap(EventMap.of(snpEventNotPresentInEventsAtThisLoc));

        final Haplotype deletionHaplotype = new Haplotype("ACTGGTCAGGTCAACTGGTCA".getBytes());
        final Event deletionEvent = new Event("20", 995, Allele.create("ACTGGTCAACT", true), Allele.create("A"));
        deletionHaplotype.setEventMap(EventMap.of(deletionEvent));

        final VariantContext spandDelVc = new VariantContextBuilder("a", "20", 1000, 1000, Arrays.asList(refAllele, Allele.SPAN_DEL)).make();

        final Haplotype deletionHaplotype2 = new Haplotype("ACTGGTCAGGTCAAGGTCA".getBytes());
        final Event deletionEvent2 = new Event("20", 995, Allele.create("ACTGGTCAACTCT", true), Allele.create("A"));
        deletionHaplotype2.setEventMap(EventMap.of(deletionEvent2));

        final VariantContext spandDelVc2 = new VariantContextBuilder("b", "20", 1000, 1000, Arrays.asList(refAllele, Allele.SPAN_DEL)).make();

        final Haplotype deletionStartingAtLocHaplotype = new Haplotype("ACTGGTCAGGTCAAGGTCA".getBytes());
        final Allele deletionStartingAtLocRefAllele = Allele.create("ACTGGTCAACTCT", true);
        final List<Allele> deletionStartingAtLocAlleles = Arrays.asList(deletionStartingAtLocRefAllele, Allele.create("A"));
        final Event deletionStartingAtLocEvent = new Event("20", 1000, deletionStartingAtLocRefAllele, Allele.create("A"));
        deletionStartingAtLocHaplotype.setEventMap(EventMap.of(deletionStartingAtLocEvent));

        final Allele remappedSNPAllele = Allele.create("GCTGGTCAACTCT");
        final VariantContext mergedSnpAndDelStartingAtLocVC = new VariantContextBuilder("a", "20", 1000, 1012,
                Arrays.asList(deletionStartingAtLocRefAllele,
                        Allele.create("A"), // for the deletion,
                        remappedSNPAllele // for the SNP
                )).make();


        final List<VariantContext> emptyGivenAllelesList = new ArrayList<>();

        final VariantContext mergedSnpAndDelVC = new VariantContextBuilder("a", "20", 1000, 1000,
                Arrays.asList(refAllele,
                        Allele.SPAN_DEL,
                        Allele.create("G"))).make();



        final List<Object[]> tests = new ArrayList<>();
        tests.add(new Object[]{
                snpEvent.convertToVariantContext("SRC"),
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype),
                Maps.asMap(new HashSet<>(snpAlleles),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });
        tests.add(new Object[]{
                mergedSnpAndDelVC,
                mergedSnpAndDelVC.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionHaplotype),
                Maps.asMap(new HashSet<>(mergedSnpAndDelVC.getAlleles()),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            if (Allele.SPAN_DEL.equals(key)) return Arrays.asList(deletionHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });
        // includes a SNP haplotype not present in events at this loc (which might happen in GGA mode)
        tests.add(new Object[]{
                snpEvent.convertToVariantContext("SRC"),
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, snpHaplotypeNotPresentInEventsAtThisLoc),
                Maps.asMap(new HashSet<>(Arrays.asList(snpEvent.refAllele(), snpEvent.altAllele())),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });

        // two spanning deletions, no given alleles -> both dels should be in event map for span del
        tests.add(new Object[]{
                mergedSnpAndDelVC,
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionHaplotype, deletionHaplotype2),
                Maps.asMap(new HashSet<>(mergedSnpAndDelVC.getAlleles()),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            if (Allele.SPAN_DEL.equals(key)) return Arrays.asList(deletionHaplotype, deletionHaplotype2);
                            return Arrays.asList(refHaplotype);
                        })
        });

        // two spanning deletions, one in given alleles
        tests.add(new Object[]{
                mergedSnpAndDelVC,
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionHaplotype, deletionHaplotype2),
                Maps.asMap(new HashSet<>(mergedSnpAndDelVC.getAlleles()),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            if (Allele.SPAN_DEL.equals(key)) return Arrays.asList(deletionHaplotype, deletionHaplotype2);
                            return Arrays.asList(refHaplotype);
                        })
        });

        // A deletion starting at the loc in the given alleles, the snp not in the given alleles
        tests.add(new Object[]{
                deletionStartingAtLocEvent.convertToVariantContext("SRC"),
                deletionStartingAtLocEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionStartingAtLocHaplotype),
                Maps.asMap(new HashSet<>(Arrays.asList(deletionStartingAtLocEvent.refAllele(), deletionStartingAtLocEvent.altAllele())),
                        (key) -> {
                            if (deletionStartingAtLocAlleles.get(1).equals(key)) return Arrays.asList(deletionStartingAtLocHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });

        // A deletion starting at the loc not in the given alleles, the snp in the given alleles
        tests.add(new Object[]{
                snpEvent.convertToVariantContext("SRC"),
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionStartingAtLocHaplotype),
                Maps.asMap(new HashSet<>(Arrays.asList(snpEvent.refAllele(), snpEvent.altAllele())),
                        (key) -> {
                            if (snpAlleles.get(1).equals(key)) return Arrays.asList(snpHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });

        // A deletion starting at the loc and the SNP in the given alleles
        tests.add(new Object[]{
                mergedSnpAndDelStartingAtLocVC,
                snpEvent.getStart(),
                Arrays.asList(snpHaplotype, refHaplotype, deletionStartingAtLocHaplotype),
                Maps.asMap(new HashSet<>(mergedSnpAndDelStartingAtLocVC.getAlleles()),
                        (key) -> {
                            if (deletionStartingAtLocAlleles.get(1).equals(key)) return Arrays.asList(deletionStartingAtLocHaplotype);
                            if (remappedSNPAllele.equals(key)) return Arrays.asList(snpHaplotype);
                            return Arrays.asList(refHaplotype);
                        })
        });


        // location forced to be after alleles - to activate Allele.SPAN_DEL insertion into the result
        HashSet<Allele>         deletionLocationAfterAlleles = new HashSet<>(Arrays.asList(deletionStartingAtLocEvent.refAllele(), deletionStartingAtLocEvent.altAllele()));
        deletionLocationAfterAlleles.add(Allele.SPAN_DEL);
        tests.add(new Object[]{
                deletionStartingAtLocEvent.convertToVariantContext("SRC"),
                deletionStartingAtLocEvent.getStart() + 1,
                Arrays.asList(snpHaplotype, refHaplotype, deletionStartingAtLocHaplotype),
                Maps.asMap(new HashSet<>(deletionLocationAfterAlleles),
                        (key) -> {
                            if ( key.equals(Allele.SPAN_DEL) ) return Arrays.asList(deletionStartingAtLocHaplotype);
                            if (deletionStartingAtLocAlleles.get(1).equals(key)) return Arrays.asList();
                            return Arrays.asList(snpHaplotype, refHaplotype);
                        })
        });

        return tests.toArray(new Object[][]{});
    }

    @Test(dataProvider = "getEventMapper")
    public void testGetEventMapper(final VariantContext mergedVc,
                                   final int loc,
                                   final List<Haplotype> haplotypes,
                                   final Map<Allele, List<Haplotype>> expectedEventMap) {
        final Map<Allele, List<Haplotype>> actualEventMap = createAlleleMapper(mergedVc, loc, haplotypes, true);
        Assert.assertEquals(actualEventMap.size(), expectedEventMap.size());
        for (final Allele key : actualEventMap.keySet()) {
            Assert.assertTrue(expectedEventMap.containsKey(key), "Got unexpected allele " + key + " with values " + actualEventMap.get(key));
            Assert.assertEquals(actualEventMap.get(key), expectedEventMap.get(key), "Lists don't match for key " + key);
        }

        for (final Allele key : expectedEventMap.keySet()) {
            Assert.assertTrue(actualEventMap.containsKey(key), "Didn't get back allele " + key);
        }
    }

    @DataProvider(name = "ConstructPhaseGroupsProvider")
    public Object[][] makeConstructPhaseGroupsData() {
        List<Object[]> tests = new ArrayList<>();

        final Allele ref = Allele.create("A", true);
        final Allele altC = Allele.create("C", false);

        final Genotype g1 = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc1 = new VariantContextBuilder().chr("20").start(1).stop(1).alleles(Arrays.asList(ref, altC)).genotypes(g1).make();
        final Genotype g2 = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc2 = new VariantContextBuilder().chr("20").start(2).stop(2).alleles(Arrays.asList(ref, altC)).genotypes(g2).make();
        final Genotype g3 = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc3 = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altC)).genotypes(g3).make();
        final List<VariantContext> calls = Arrays.asList(vc1, vc2, vc3);

        // test no phased variants, empty map
        final Map<VariantContext, Pair<Integer, PhaseGroup>> nonePhased1 = new HashMap<>();
        tests.add(new Object[]{calls, nonePhased1, 0, 0, 0, calls, null});

        // test no phased variants, full map, exception expected
        final IllegalStateException tooSmallPhaseGroupException = new IllegalStateException("Somehow we have a group of phased variants that has fewer than 2 members");

        final Map<VariantContext, Pair<Integer, PhaseGroup>> nonePhased2 = new HashMap<>();
        nonePhased2.put(vc1, Pair.of(0, PhaseGroup.PHASE_01));
        nonePhased2.put(vc2, Pair.of(1, PhaseGroup.PHASE_01));
        nonePhased2.put(vc3, Pair.of(2, PhaseGroup.PHASE_01));
        tests.add(new Object[]{calls, nonePhased2, 3, -1, -1, calls, tooSmallPhaseGroupException});

        // test 2 phased variants
        final Genotype g1P = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).phased(true).make();
        final VariantContext vc1P = new VariantContextBuilder().chr("20").start(1).stop(1).alleles(Arrays.asList(ref, altC)).genotypes(g1P).make();
        final Genotype g2P = new GenotypeBuilder().alleles(Arrays.asList(altC, ref)).phased(true).make();
        final VariantContext vc2P = new VariantContextBuilder().chr("20").start(2).stop(2).alleles(Arrays.asList(ref, altC)).genotypes(g2P).make();
        final List<VariantContext> phasedCalls = Arrays.asList(vc1P, vc2P, vc3);

        final Map<VariantContext, Pair<Integer, PhaseGroup>> twoPhased = new HashMap<>();
        twoPhased.put(vc1, Pair.of(0, PhaseGroup.PHASE_01));
        twoPhased.put(vc2, Pair.of(0, PhaseGroup.PHASE_10));
        tests.add(new Object[]{calls, twoPhased, 1, 1, 2, phasedCalls, null});

        // test all phased variants
        final Genotype g3P = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).phased(true).make();
        final VariantContext vc3P = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altC)).genotypes(g3P).make();
        final List<VariantContext> phasedCalls2 = Arrays.asList(vc1P, vc2P, vc3P);

        final Map<VariantContext, Pair<Integer, PhaseGroup>> allPhased = new HashMap<>();
        allPhased.put(vc1, Pair.of(0, PhaseGroup.PHASE_01));
        allPhased.put(vc2, Pair.of(0, PhaseGroup.PHASE_10));
        allPhased.put(vc3, Pair.of(0, PhaseGroup.PHASE_01));
        tests.add(new Object[]{calls, allPhased, 1, 1, 3, phasedCalls2, null});

        // test a spanning deletion case: unphased snp, deletion, spanned snp
        final Allele delRef = Allele.create("AA", true);
        final Allele delAlt = Allele.create("A", false);


        final Genotype g4 = new GenotypeBuilder().alleles(Arrays.asList(delRef, delAlt)).make();
        final VariantContext vc4 = new VariantContextBuilder().chr("20").start(3).stop(4).alleles(Arrays.asList(delRef, delAlt)).genotypes(g4).make();
        final Genotype g5 = new GenotypeBuilder().alleles(Arrays.asList(Allele.SPAN_DEL, altC)).make();
        final VariantContext vc5 = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, Allele.SPAN_DEL, altC)).genotypes(g5).make();

        final Genotype g4P = new GenotypeBuilder().alleles(Arrays.asList(delRef, delAlt)).phased(true).make();
        final VariantContext vc4P = new VariantContextBuilder().chr("20").start(3).stop(4).alleles(Arrays.asList(delRef, delAlt)).genotypes(g4P).make();
        final Genotype g5P = new GenotypeBuilder().alleles(Arrays.asList(altC, Allele.SPAN_DEL)).phased(true).make();
        final VariantContext vc5P = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, Allele.SPAN_DEL, altC)).genotypes(g5P).make();

        final List<VariantContext> spanningDeletionCalls = Arrays.asList(vc1, vc4, vc5);
        final List<VariantContext> spanningDeletionPhasedCalls = Arrays.asList(vc1, vc4P, vc5P);

        final Map<VariantContext, Pair<Integer, PhaseGroup>> phasedSpanDel = new HashMap<>();
        phasedSpanDel.put(vc4, Pair.of(0, PhaseGroup.PHASE_01));
        phasedSpanDel.put(vc5, Pair.of(0, PhaseGroup.PHASE_10));
        tests.add(new Object[]{spanningDeletionCalls, phasedSpanDel, 1, 1, 2, spanningDeletionPhasedCalls, null});

        return tests.toArray(new Object[][]{});
    }

    @Test(dataProvider="ConstructPhaseGroupsProvider")
    public void testConstructPhaseGroups(final List<VariantContext> calls,
                                         final Map<VariantContext, Pair<Integer, PhaseGroup>> phaseMap,
                                         final int endIndex,
                                         final int expectedNumGroups,
                                         final int expectedGroupSize,
                                         final List<VariantContext> expectedPhasedCalls,
                                         final Exception expectedException) {
        final List<VariantContext> actualPhasedCalls;
        try {
            actualPhasedCalls = constructPhaseGroups(calls, phaseMap, endIndex);
        } catch (IllegalStateException e) {
            Assert.assertEquals(e.getMessage(), expectedException.getMessage());
            return;
        }

        final Set<String> uniqueGroups = new HashSet<>();
        int counter = 0;
        int vcIdx = 0;
        for ( final VariantContext call : actualPhasedCalls ) {
            int gtIdx = 0;
            for ( final Genotype g : call.getGenotypes() ) {
                if ( g.hasExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY) ) {
                    uniqueGroups.add(g.getExtendedAttribute(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY).toString());
                    Assert.assertEquals(g.getGenotypeString(), expectedPhasedCalls.get(vcIdx).getGenotype(gtIdx).getGenotypeString());
                    counter++;
                }
                gtIdx++;
            }
            vcIdx++;
        }

        Assert.assertEquals(uniqueGroups.size(), expectedNumGroups);
        Assert.assertEquals(counter, expectedGroupSize);
    }

    @DataProvider(name = "ConstructPhaseSetMappingProvider")
    public Object[][] makeConstructPhaseSetMappingData() {
        List<Object[]> tests = new ArrayList<Object[]>();

        final Allele ref = Allele.create("A", true);
        final Allele altC = Allele.create("C", false);
        final Allele altT = Allele.create("T", false);

        final VariantContext vc1 = new VariantContextBuilder().chr("20").start(1).stop(1).alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc2 = new VariantContextBuilder().chr("20").start(2).stop(2).alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc3 = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altT)).make();
        final VariantContext vc4 = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc5 = new VariantContextBuilder().chr("20").start(5).stop(5).alleles(Arrays.asList(ref, altC)).make();
        final List<VariantContext> calls = Arrays.asList(vc2, vc3, vc4);

        final Haplotype pos1 = new Haplotype("CAAAA".getBytes());
        pos1.setEventMap(EventMap.of(vcToEvent(vc1)));
        final Haplotype pos2 = new Haplotype("ACAAA".getBytes());
        pos2.setEventMap(EventMap.of(vcToEvent(vc2)));
        final Haplotype pos3 = new Haplotype("AACAA".getBytes());
        pos3.setEventMap(EventMap.of(vcToEvent(vc3)));
        final Haplotype pos4 = new Haplotype("AAACA".getBytes());
        pos4.setEventMap(EventMap.of(vcToEvent(vc4)));
        final Haplotype pos24 = new Haplotype("ACACA".getBytes());
        pos24.setEventMap(EventMap.of(vcToEvent(vc2), vcToEvent(vc4)));
        final Haplotype pos34 = new Haplotype("AACCA".getBytes());
        pos34.setEventMap(EventMap.of(vcToEvent(vc3), vcToEvent(vc4)));
        final Haplotype pos234 = new Haplotype("ACCCA".getBytes());
        pos234.setEventMap(EventMap.of(vcToEvent(vc2), vcToEvent(vc3), vcToEvent(vc4)));
        final Haplotype pos23 = new Haplotype("ACCAA".getBytes());
        pos24.setEventMap(EventMap.of(vcToEvent(vc2), vcToEvent(vc3)));

        final Map<VariantContext, Set<Haplotype>> haplotypeMap = new HashMap<>();

        // note: the references to genotype below are referring to the state of input sample. the method we are
        // testing only views alternate haplotypes, so it has no way of knowing if a variant is truly homozygous
        // or just appears on all alternate haplotypes.

        // test 1: no phased variants #1
        final Set<Haplotype> haplotypes2 = new HashSet<>();
        haplotypes2.add(pos2);
        haplotypeMap.put(vc2, haplotypes2);
        tests.add(new Object[]{Arrays.asList(vc2), new HashMap<>(haplotypeMap), 1, 0, 0, 0, 0});

        // test 2: opposite phase
        final Set<Haplotype> haplotypes3 = new HashSet<>();
        haplotypes3.add(pos3);
        haplotypeMap.put(vc3, haplotypes3);
        tests.add(new Object[]{Arrays.asList(vc2, vc3), new HashMap<>(haplotypeMap), 2, 2, 1, 1, 1});

        // test 3: no phased variants (a third call is out of phase)
        final Set<Haplotype> haplotypes4 = new HashSet<>();
        haplotypes4.add(pos4);
        haplotypeMap.put(vc4, haplotypes4);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap), 3, 0, 0, 0, 0});

        // test 4: mixture
        final Set<Haplotype> haplotypes24 = new HashSet<>();
        haplotypes24.add(pos24);
        haplotypeMap.put(vc2, haplotypes24);
        haplotypeMap.put(vc4, haplotypes24);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap), 2, 3, 1, 2, 1});

        // test 5: 2 hets
        haplotypeMap.remove(vc3);
        tests.add(new Object[]{Arrays.asList(vc2, vc4), new HashMap<>(haplotypeMap), 1, 2, 1, 2, 0});

        // test 6: two snps with opposite phase
        final Set<Haplotype> haplotypes1 = new HashSet<>();
        haplotypes1.add(pos1);
        haplotypeMap.put(vc1, haplotypes1);
        tests.add(new Object[]{Arrays.asList(vc1, vc2, vc4), new HashMap<>(haplotypeMap), 2, 3, 1, 1, 2});

        // test 7: homs around a het
        final Map<VariantContext, Set<Haplotype>> haplotypeMap7 = new HashMap<>();
        final Set<Haplotype> haplotypes2hom = new HashSet<>();
        haplotypes2hom.add(pos24);
        haplotypes2hom.add(pos234);
        final Set<Haplotype> haplotypes4hom = new HashSet<>();
        haplotypes4hom.add(pos24);
        haplotypes4hom.add(pos234);
        final Set<Haplotype> haplotypes3het = new HashSet<>();
        haplotypes3het.add(pos234);
        haplotypeMap7.put(vc2, haplotypes2hom);
        haplotypeMap7.put(vc3, haplotypes3het);
        haplotypeMap7.put(vc4, haplotypes4hom);
        tests.add(new Object[]{calls, haplotypeMap7, 2, 3, 1, 3, 0});

        // test 8: hets around a hom
        final Map<VariantContext, Set<Haplotype>> haplotypeMap8 = new HashMap<>();
        final Set<Haplotype> haplotypes2het = new HashSet<>();
        haplotypes2het.add(pos234);
        final Set<Haplotype> haplotypes4het = new HashSet<>();
        haplotypes4het.add(pos234);
        final Set<Haplotype> haplotypes3hom = new HashSet<>();
        haplotypes3hom.add(pos3);
        haplotypes3hom.add(pos234);
        haplotypeMap8.put(vc2, haplotypes2het);
        haplotypeMap8.put(vc3, haplotypes3hom);
        haplotypeMap8.put(vc4, haplotypes4het);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap8), 2, 3, 1, 3, 0});

        // test 9: no phased variants around a hom
        final Map<VariantContext, Set<Haplotype>> haplotypeMap9 = new HashMap<>();
        final Set<Haplotype> haplotypes2incomplete = new HashSet<>();
        haplotypes2incomplete.add(pos24);
        final Set<Haplotype> haplotypes3incomplete = new HashSet<>();
        haplotypes3incomplete.add(pos34);
        final Set<Haplotype> haplotypes4complete = new HashSet<>();
        haplotypes4complete.add(pos24);
        haplotypes4complete.add(pos34);
        haplotypes4complete.add(pos234);
        haplotypeMap9.put(vc2, haplotypes2incomplete);
        haplotypeMap9.put(vc3, haplotypes3incomplete);
        haplotypeMap9.put(vc4, haplotypes4complete);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap9), 3, 0, 0, 0, 0});

        // test 10: snp spanned by overlapping deletion
        final Allele refForDel = Allele.create("AG", true);
        final Allele altDel = Allele.create("A", false);

        final VariantContext delVC = new VariantContextBuilder().chr("20").start(3).stop(4).alleles(Arrays.asList(refForDel, altDel)).make();
        final VariantContext spannedSnpVC = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, Allele.SPAN_DEL, altT)).make();

        // the ref haplotype would be "TAGCA"
        final Haplotype spandelHap = new Haplotype("TACA".getBytes());
        spandelHap.setEventMap(EventMap.of(vcToEvent(delVC)));

        final Haplotype spannedSnp = new Haplotype("TATCA".getBytes());
        // set the event map to just the SNP, not the SPAN_DEL symbolic allele, from spannedSnpVC
        spannedSnp.setEventMap(EventMap.of(new Event(spannedSnpVC.getContig(), spannedSnpVC.getStart(), spannedSnpVC.getReference(), altT)));

        final Set<Haplotype> haplotypesWithSpanDel = new HashSet<>();
        haplotypesWithSpanDel.add(spandelHap);

        final Set<Haplotype> haplotypesWithSpannedSNP = new HashSet<>();
        haplotypesWithSpannedSNP.add(spannedSnp);

        final Map<VariantContext, Set<Haplotype>> spanDelHapMap = new HashMap<>();
        spanDelHapMap.put(delVC, haplotypesWithSpanDel);
        spanDelHapMap.put(spannedSnpVC, haplotypesWithSpannedSNP);

        final List<VariantContext> spandelCalls = new ArrayList<>();
        spandelCalls.add(delVC);
        spandelCalls.add(spannedSnpVC);

        tests.add(new Object[]{spandelCalls, new HashMap<>(spanDelHapMap), 2, 2, 1, 1, 1});

        // test 11: a hom followed by two opposite-phase hets
        final Map<VariantContext, Set<Haplotype>> haplotypeMap11 = new HashMap<>();
        final Set<Haplotype> haplotypes2hom2 = new HashSet<>();
        haplotypes2hom2.add(pos24);
        haplotypes2hom2.add(pos23);
        final Set<Haplotype> haplotypes3het2 = new HashSet<>();
        haplotypes3het2.add(pos23);
        final Set<Haplotype> haplotypes4het2 = new HashSet<>();
        haplotypes4het2.add(pos24);
        haplotypeMap11.put(vc2, haplotypes2hom2);
        haplotypeMap11.put(vc3, haplotypes3het2);
        haplotypeMap11.put(vc4, haplotypes4het2);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap11), 2, 3, 1, 2, 1});


        // test 12: opposite-phase hets followed by a hom
        final Map<VariantContext, Set<Haplotype>> haplotypeMap12 = new HashMap<>();
        final Set<Haplotype> haplotypes2het12 = new HashSet<>();
        haplotypes2het12.add(pos24);

        final Set<Haplotype> haplotypes3het12 = new HashSet<>();
        haplotypes3het12.add(pos34);

        final Set<Haplotype> haplotypes4hom12 = new HashSet<>();
        haplotypes4hom12.add(pos24);
        haplotypes4hom12.add(pos34);
        haplotypeMap12.put(vc2, haplotypes2het12);
        haplotypeMap12.put(vc3, haplotypes3het12);
        haplotypeMap12.put(vc4, haplotypes4hom12);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap12), 2, 3, 1, 2, 1});

        // test 13: opposite-phase hets surrounding a hom
        final Map<VariantContext, Set<Haplotype>> haplotypeMap13 = new HashMap<>();
        final Set<Haplotype> haplotypes2het13 = new HashSet<>();
        haplotypes2het13.add(pos23);

        final Set<Haplotype> haplotypes3hom13 = new HashSet<>();
        haplotypes3hom13.add(pos23);
        haplotypes3hom13.add(pos34);

        final Set<Haplotype> haplotypes4het13 = new HashSet<>();
        haplotypes4het13.add(pos34);
        haplotypeMap13.put(vc2, haplotypes2het13);
        haplotypeMap13.put(vc3, haplotypes3hom13);
        haplotypeMap13.put(vc4, haplotypes4het13);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap13), 2, 3, 1, 2, 1});

        // test 14: two hets on the same haplotype surrounding an opposite hap het
        final Map<VariantContext, Set<Haplotype>> haplotypeMap14 = new HashMap<>();
        final Set<Haplotype> haplotypes2het14 = new HashSet<>();
        haplotypes2het14.add(pos24);

        final Set<Haplotype> haplotypes3het14 = new HashSet<>();
        haplotypes3het14.add(pos3);

        final Set<Haplotype> haplotypes4het14 = new HashSet<>();
        haplotypes4het14.add(pos24);
        haplotypeMap14.put(vc2, haplotypes2het14);
        haplotypeMap14.put(vc3, haplotypes3het14);
        haplotypeMap14.put(vc4, haplotypes4het14);
        tests.add(new Object[]{calls, new HashMap<>(haplotypeMap14), 2, 3, 1, 2, 1});

        // we should have a test for a case that returns two phase groups
        // test 15: create two phase groups broken by an unphased SNP
        final Map<VariantContext, Set<Haplotype>> haplotypeMap15 = new HashMap<>();
        final Haplotype pos12 = new Haplotype("CCAAA".getBytes());
        pos12.setEventMap(EventMap.of(vcToEvent(vc1), vcToEvent(vc2)));
        final Haplotype pos1245 = new Haplotype("CCACC".getBytes());
        pos12.setEventMap(EventMap.of(vcToEvent(vc1), vcToEvent(vc2), vcToEvent(vc4), vcToEvent(vc5)));
        final Haplotype pos45 = new Haplotype("AAACC".getBytes());
        pos45.setEventMap(EventMap.of(vcToEvent(vc4), vcToEvent(vc5)));

        final Set<Haplotype> haplotypes1het15 = new HashSet<>();
        haplotypes1het15.add(pos12);
        haplotypes1het15.add(pos1245);

        final Set<Haplotype> haplotypes2het15 = new HashSet<>();
        haplotypes2het15.add(pos12);
        haplotypes2het15.add(pos1245);

        final Set<Haplotype> haplotypes3het15 = new HashSet<>();
        haplotypes3het15.add(pos3);

        final Set<Haplotype> haplotypes4het15 = new HashSet<>();
        haplotypes4het15.add(pos45);
        haplotypes4het15.add(pos1245);

        final Set<Haplotype> haplotypes5het15 = new HashSet<>();
        haplotypes5het15.add(pos45);
        haplotypes5het15.add(pos1245);

        haplotypeMap15.put(vc1, haplotypes1het15);
        haplotypeMap15.put(vc2, haplotypes2het15);
        haplotypeMap15.put(vc3, haplotypes3het15);
        haplotypeMap15.put(vc4, haplotypes4het15);
        haplotypeMap15.put(vc5, haplotypes5het15);
        // this will end up with two phase groups, with both pairs of alts assigned to 0|1 within their respective groups
        tests.add(new Object[]{Arrays.asList(vc1, vc2, vc3, vc4, vc5), new HashMap<>(haplotypeMap15), 4, 4, 2, 4, 0});


        return tests.toArray(new Object[][]{});
    }

    private int getTotalHaplotypes(final Map<VariantContext, Set<Haplotype>> haplotypeMap) {
        final Set<Haplotype> haplotypesWithCalledVariants = new HashSet<>();
        haplotypeMap.values().forEach(haplotypesWithCalledVariants::addAll);
        return haplotypesWithCalledVariants.size();
    }

    @Test(dataProvider="ConstructPhaseSetMappingProvider")
    public void testConstructPhaseSetMapping(final List<VariantContext> calls,
                                             final Map<VariantContext, Set<Haplotype>> haplotypeMap,
                                             final int totalHaplotypes,
                                             final int expectedMapSize,
                                             final int expectedNumGroups,
                                             final int expectedNum01,
                                             final int expectedNum10) {
        Assert.assertEquals(totalHaplotypes, getTotalHaplotypes(haplotypeMap));
        final Map<VariantContext, Pair<Integer, PhaseGroup>> actualPhaseSetMapping =
                constructPhaseSetMapping(calls, haplotypeMap);
        final int actualNumGroups = Math.toIntExact(actualPhaseSetMapping.values().stream().map(Pair::getLeft).distinct().count());
        Assert.assertEquals(actualNumGroups, expectedNumGroups);
        Assert.assertEquals(actualPhaseSetMapping.size(), expectedMapSize);

        int num01 = 0, num10 = 0;
        for ( final Pair<Integer, PhaseGroup> phase : actualPhaseSetMapping.values() ) {
            if ( phase.getRight().equals(PhaseGroup.PHASE_01) )
                num01++;
            else if ( phase.getRight().equals(PhaseGroup.PHASE_10) )
                num10++;
        }
        Assert.assertEquals(num01, expectedNum01);
        Assert.assertEquals(num10, expectedNum10);
    }

    @DataProvider(name = "CreateHaplotypeMappingProvider")
    public Object[][] makeCreateHaplotypeMappingData() {
        List<Object[]> tests = new ArrayList<Object[]>();

        final Set<Haplotype> haplotypes = new HashSet<>();
        final Allele ref = Allele.create("A", true);
        final Allele altC = Allele.create("C", false);
        final Allele altT = Allele.create("T", false);

        final Haplotype AtoC1 = new Haplotype("AACAA".getBytes());
        final VariantContext vc1 = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altC)).make();
        AtoC1.setEventMap(EventMap.of(vcToEvent(vc1)));
        haplotypes.add(AtoC1);

        final Haplotype AtoC2 = new Haplotype("AAACA".getBytes());
        final VariantContext vc2 = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, altT)).make();
        AtoC2.setEventMap(EventMap.of(vcToEvent(vc2)));
        haplotypes.add(AtoC2);

        final VariantContext spannedSnpVC = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, altT, Allele.SPAN_DEL)).make();

        final Haplotype spandelHap = new Haplotype("AAAA".getBytes());

        final List<Allele> deletionAlleles = Arrays.asList(Allele.create("AA", true), Allele.create("A"));
        final VariantContextBuilder deletionVCBuilder = new VariantContextBuilder("a", "20", 3, 4, deletionAlleles);
        final VariantContext deletionVc = deletionVCBuilder.make();
        spandelHap.setEventMap(EventMap.of(vcToEvent(deletionVc)));

        final Set<Haplotype> haplotypesWithSpanDel = new HashSet<>(haplotypes);
        haplotypesWithSpanDel.add(spandelHap);

        tests.add(new Object[]{vc1, haplotypes, AtoC1});
        tests.add(new Object[]{vc2, haplotypes, AtoC2});
        tests.add(new Object[]{new VariantContextBuilder().chr("20").start(1).stop(1).alleles(Arrays.asList(ref, altT)).make(), haplotypes, null});
        tests.add(new Object[]{spannedSnpVC, haplotypesWithSpanDel, AtoC2});

        return tests.toArray(new Object[][]{});
    }

    @Test(dataProvider="CreateHaplotypeMappingProvider")
    public void testCreateHaplotypeMapping(final VariantContext vc, final Set<Haplotype> haplotypes, final Haplotype expected) {
        final Map<VariantContext, Set<Haplotype>> mapping = constructHaplotypeMapping(Arrays.asList(vc), haplotypes);
        final Set<Haplotype> actual = mapping.get(vc);
        if ( expected == null )
            Assert.assertTrue(actual.isEmpty(), actual.toString());
        else {
            Assert.assertEquals(actual.size(), 1);
            Assert.assertEquals(actual.iterator().next(), expected);
        }
    }

    @DataProvider(name = "PhaseCallsDataProvider")
    public Object[][] makePhaseCallsData() {
        List<Object[]> tests = new ArrayList<Object[]>();


        final Allele ref = Allele.create("A", true);
        final Allele altC = Allele.create("C", false);
        final Allele altT = Allele.create("T", false);

        final Genotype vc1GT = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc1 = new VariantContextBuilder().chr("20").start(2).stop(2).alleles(Arrays.asList(ref, altC)).genotypes(vc1GT).make();
        final Genotype vc2GT = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).make();
        final VariantContext vc2 = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altC)).genotypes(vc2GT).make();
        final Genotype vc3GT = new GenotypeBuilder().alleles(Arrays.asList(ref, altT)).make();
        final VariantContext vc3 = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, altT)).genotypes(vc3GT).make();
        final Genotype vc4GT = new GenotypeBuilder().alleles(Arrays.asList(ref, altT)).make();
        final VariantContext vc4 = new VariantContextBuilder().chr("20").start(5).stop(5).alleles(Arrays.asList(ref, altT)).genotypes(vc4GT).make();
        final List<VariantContext> calls = Arrays.asList(vc1, vc2, vc3, vc4);

        final Set<Haplotype> haplotypes = new HashSet<>();

        final Haplotype hap1 = new Haplotype("ACATAA".getBytes());
        hap1.setEventMap(EventMap.of(vcToEvent(vc1), vcToEvent(vc3)));
        haplotypes.add(hap1);

        final Haplotype hap2 = new Haplotype("AACATA".getBytes());
        hap2.setEventMap(EventMap.of(vcToEvent(vc2), vcToEvent(vc4)));
        haplotypes.add(hap2);


        final Genotype vc1PGT = new GenotypeBuilder().alleles(Arrays.asList(ref, altC)).phased(true).make();
        final VariantContext vc1P = new VariantContextBuilder().chr("20").start(2).stop(2).alleles(Arrays.asList(ref, altC)).genotypes(vc1PGT).make();
        final Genotype vc2PGT = new GenotypeBuilder().alleles(Arrays.asList(altC, ref)).phased(true).make();
        final VariantContext vc2P = new VariantContextBuilder().chr("20").start(3).stop(3).alleles(Arrays.asList(ref, altC)).genotypes(vc2PGT).make();
        final Genotype vc3PGT = new GenotypeBuilder().alleles(Arrays.asList(ref, altT)).phased(true).make();
        final VariantContext vc3P = new VariantContextBuilder().chr("20").start(4).stop(4).alleles(Arrays.asList(ref, altT)).genotypes(vc3PGT).make();
        final Genotype vc4PGT = new GenotypeBuilder().alleles(Arrays.asList(altT, ref)).phased(true).make();
        final VariantContext vc4P = new VariantContextBuilder().chr("20").start(5).stop(5).alleles(Arrays.asList(ref, altT)).genotypes(vc4PGT).make();
        final List<VariantContext> phasedCalls = Arrays.asList(vc1P, vc2P, vc3P, vc4P);

        tests.add(new Object[]{calls, haplotypes, phasedCalls});


        // add a fifth uncalled VC and haplotype
        final Genotype vc5GT = new GenotypeBuilder().alleles(Arrays.asList(ref, altT)).make();
        final VariantContext vc5Uncalled = new VariantContextBuilder().chr("20").start(6).stop(6).alleles(Arrays.asList(ref, altT)).genotypes(vc5GT).make();

        final Set<Haplotype> haplotypesPlusUncalledVariant = new HashSet<>(haplotypes);
        final Haplotype hap3 = new Haplotype("AAAAAT".getBytes());
        hap3.setEventMap(EventMap.of(vcToEvent(vc5Uncalled)));
        haplotypesPlusUncalledVariant.add(hap3);

        tests.add(new Object[]{calls, haplotypesPlusUncalledVariant, phasedCalls});

        return tests.toArray(new Object[][]{});
    }

    @Test(dataProvider="PhaseCallsDataProvider")
    public void testPhaseCalls(final List<VariantContext> calls, final Set<Haplotype> calledHaplotypes, final List<VariantContext> expectedPhasedCalls) {
        final List<VariantContext> actualPhasedCalls = phaseCalls(calls, calledHaplotypes);
        Assert.assertEquals(actualPhasedCalls.size(), expectedPhasedCalls.size());
        for (int i = 0; i < expectedPhasedCalls.size(); i++) {
            VariantContextTestUtils.assertVariantContextsAreEqual(actualPhasedCalls.get(i), expectedPhasedCalls.get(i), new ArrayList<>(), Collections.emptyList());
            Assert.assertEquals(actualPhasedCalls.get(i).getSource(), expectedPhasedCalls.get(i).getSource());
        }

    }

    @Test
    public void testAddGivenAlleles() {
        final int assemblyRegionStart = 1;
        final int maxMnpDistance = 0;
        final SmithWatermanAligner aligner = SmithWatermanAligner.getAligner(SmithWatermanAligner.Implementation.FASTEST_AVAILABLE);
        final AssemblyResultSet assemblyResultSet = new AssemblyResultSet();

        final Haplotype refHaplotype = new Haplotype("AAAACCCCGGGGTTTT".getBytes(), true);
        final byte[] fullReferenceWithPadding = ("A" + refHaplotype.getBaseString()).getBytes();
        refHaplotype.setAlignmentStartHapwrtRef(assemblyRegionStart);
        refHaplotype.setCigar(new Cigar(Collections.singletonList(new CigarElement(refHaplotype.length(), CigarOperator.M))));
        refHaplotype.setGenomeLocation(new SimpleInterval("chr", assemblyRegionStart, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.setPaddedReferenceLoc(new SimpleInterval("chr", 1, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.add(refHaplotype);
        assemblyResultSet.setFullReferenceWithPadding(fullReferenceWithPadding);

        // add a SNP
        final Event givenEvent = new Event("chr", 2, Allele.create((byte) 'A', true), Allele.create((byte) 'C'));

        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 2);
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(1).getBaseString(), "ACAACCCCGGGGTTTT");


        // adding the same VC should have no effect
        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 2);

        // add another SNP
        final Event givenEvent2 = new Event("chr", 5, Allele.create((byte) 'C', true), Allele.create((byte) 'G'));
        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent2), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        // SNP is not found in existing variation, so it's added to the ref and the first SNP
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 4);
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(2).getBaseString(), "AAAAGCCCGGGGTTTT");
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(3).getBaseString(), "ACAAGCCCGGGGTTTT");

        // add a deletion that overlaps the second SNP.  This variant gets added to the ref and first SNP haplotypes but not either
        // haplotype that contains the overlapping 2nd SNP
        final Event givenEvent3 = new Event("chr", 5, Allele.create("CCC".getBytes(), true), Allele.create((byte) 'C'));
        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent3), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 6);
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(4).getBaseString(), "AAAACCGGGGTTTT");
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(5).getBaseString(), "ACAACCGGGGTTTT");

        // adding the same deletion should do nothing
        final Event givenEvent4 = new Event("chr", 5, Allele.create("CCC".getBytes(), true), Allele.create("C".getBytes(), false));
        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent4), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 6);

        // finally, add a haplotype with two new phased SNPs, after which adding an allele with one of these SNPs does nothing
        final Haplotype phasedHaplotype = new Haplotype("AAAACCTCGAGGTTTT".getBytes(), false);
        phasedHaplotype.setAlignmentStartHapwrtRef(assemblyRegionStart);
        phasedHaplotype.setCigar(new Cigar(Collections.singletonList(new CigarElement(refHaplotype.length(), CigarOperator.M))));
        phasedHaplotype.setGenomeLocation(new SimpleInterval("chr", assemblyRegionStart, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.add(phasedHaplotype);
        assemblyResultSet.regenerateVariationEvents(maxMnpDistance);

        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 7);


        final Event givenEvent5 = new Event("chr", 8, Allele.create((byte) 'C', true), Allele.create((byte) 'T'));
        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent5), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 7);
    }

    @Test
    public void testAddMultiallelicGivenAlleles() {
        final int assemblyRegionStart = 1;
        final int maxMnpDistance = 0;
        final SmithWatermanAligner aligner = SmithWatermanAligner.getAligner(SmithWatermanAligner.Implementation.FASTEST_AVAILABLE);
        final AssemblyResultSet assemblyResultSet = new AssemblyResultSet();

        final Haplotype refHaplotype = new Haplotype("AAAACCCCGGGGTTTT".getBytes(), true);
        final byte[] fullReferenceWithPadding = ("A" + refHaplotype.getBaseString()).getBytes();
        refHaplotype.setAlignmentStartHapwrtRef(assemblyRegionStart);
        refHaplotype.setCigar(new Cigar(Collections.singletonList(new CigarElement(refHaplotype.length(), CigarOperator.M))));
        refHaplotype.setGenomeLocation(new SimpleInterval("chr", assemblyRegionStart, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.setPaddedReferenceLoc(new SimpleInterval("chr", 1, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.add(refHaplotype);
        assemblyResultSet.setFullReferenceWithPadding(fullReferenceWithPadding);

        // add two SNPs at the same locus
        final Event givenEvent1 = new Event("chr", 2, Allele.create((byte) 'A', true), Allele.create((byte) 'C'));
        final Event givenEvent2 = new Event("chr", 2, Allele.create((byte) 'A', true), Allele.create((byte) 'T'));

        assemblyResultSet.addGivenAlleles(List.of(givenEvent1, givenEvent2), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 3);
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(1).getBaseString(), "ACAACCCCGGGGTTTT");
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(2).getBaseString(), "ATAACCCCGGGGTTTT");
    }

    @Test
    public void testGivenAllelesHugeInsertion() {
        final int assemblyRegionStart = 1;
        final int maxMnpDistance = 0;
        final SmithWatermanAligner aligner = SmithWatermanAligner.getAligner(SmithWatermanAligner.Implementation.FASTEST_AVAILABLE);
        final AssemblyResultSet assemblyResultSet = new AssemblyResultSet();

        final Haplotype refHaplotype = new Haplotype("AAAACCCCGGGGTTTT".getBytes(), true);
        final byte[] fullReferenceWithPadding = ("A" + refHaplotype.getBaseString()).getBytes();
        refHaplotype.setAlignmentStartHapwrtRef(assemblyRegionStart);
        refHaplotype.setCigar(new Cigar(Collections.singletonList(new CigarElement(refHaplotype.length(), CigarOperator.M))));
        refHaplotype.setGenomeLocation(new SimpleInterval("chr", assemblyRegionStart, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.setPaddedReferenceLoc(new SimpleInterval("chr", 1, assemblyRegionStart + refHaplotype.length()));
        assemblyResultSet.add(refHaplotype);
        assemblyResultSet.setFullReferenceWithPadding(fullReferenceWithPadding);

        Utils.resetRandomGenerator();
        final byte[] insertedBases = new byte[200];
        BaseUtils.fillWithRandomBases(insertedBases, 0, insertedBases.length);


        // add huge insertion
        final Event givenEvent = new Event("chr", 2, Allele.create((byte) 'A', true), Allele.create('A' + new String(insertedBases)));

        assemblyResultSet.addGivenAlleles(Collections.singletonList(givenEvent), maxMnpDistance,
                aligner, HAPLOTYPE_TO_REFERENCE_SW_PARAMETERS);
        Assert.assertEquals(assemblyResultSet.getHaplotypeCount(), 2);
        Assert.assertEquals(assemblyResultSet.getHaplotypeList().get(1).getBaseString(), "AA" + new String(insertedBases) + "AACCCCGGGGTTTT");
    }


    @DataProvider(name = "filterPileupHaplotypesDataProvider")
    public Object[][] filterPileupHaplotypesDataProvider() {
        final Haplotype hapA = new Haplotype("ACCTGTA".getBytes());
        final Haplotype hapB = new Haplotype("ATCTGTA".getBytes());
        final Haplotype hapC = new Haplotype("ATCTGAA".getBytes());
        final Haplotype hapD = new Haplotype("ACCTGAA".getBytes());
        final Haplotype hapF = new Haplotype("GAAGAAG".getBytes()); // testing repeated kmers

        Map<Kmer, Integer> flatSupportAllKmers = new HashMap<Kmer, Integer>() {
            private static final long serialVersionUID = 0L; {
            put(new Kmer("ACC"), 1);
            put(new Kmer("CCT"), 1);
            put(new Kmer("CTG"), 1);
            put(new Kmer("TGT"), 1);
            put(new Kmer("GTA"), 1);
            put(new Kmer("ATC"), 1);
            put(new Kmer("TCT"), 1);
            put(new Kmer("TGA"), 1);
            put(new Kmer("GAA"), 1);
            put(new Kmer("AGA"), 1);
            put(new Kmer("AAG"), 1);
        }};

        Map<Kmer, Integer> hapDKmersHighSupport = new HashMap<Kmer, Integer>() {
            private static final long serialVersionUID = 0L;{
            put(new Kmer("ACC"), 10);
            put(new Kmer("CCT"), 10);
            put(new Kmer("CTG"), 10);
            put(new Kmer("TGT"), 1);
            put(new Kmer("GTA"), 1);
            put(new Kmer("ATC"), 1);
            put(new Kmer("TCT"), 1);
            put(new Kmer("TGA"), 10);
            put(new Kmer("GAA"), 10);
            put(new Kmer("AGA"), 1);
            put(new Kmer("AAG"), 1);
        }};

        Map<Kmer, Integer> hapDKmers = new HashMap<Kmer, Integer>() {
            private static final long serialVersionUID = 0L; {
            put(new Kmer("ACC"), 10);
            put(new Kmer("CCT"), 10);
            put(new Kmer("CTG"), 10);
            put(new Kmer("TGA"), 10);
            put(new Kmer("GAA"), 10);
        }};

        Map<Kmer, Integer> hapFRepeatedKmers = new HashMap<Kmer, Integer>() {
            private static final long serialVersionUID = 0L; {
            put(new Kmer("GAA"), 1);
        }};


        Object[][] tests = new Object[][] {
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),flatSupportAllKmers,5,3,Arrays.asList(hapA,hapB,hapC,hapD)}, //returns all when no filtering required
                // These haplotypes are all equivalent, these test stability of the filtering
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),flatSupportAllKmers,1,3,Arrays.asList(hapA,hapB,hapC,hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),flatSupportAllKmers,2,3,Arrays.asList(hapA,hapB,hapC,hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),flatSupportAllKmers,3,3,Arrays.asList(hapA,hapB,hapC,hapD)},

                // Repetitive kmers in hapF don't get double counted
                new Object[]{Arrays.asList(hapA,hapB,hapD,hapF),hapFRepeatedKmers,2,3,Arrays.asList(hapF,hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapD,hapF),hapFRepeatedKmers,1,3,Arrays.asList(hapF, hapD)}, //currently repeated kmers only count as singular evidence

                // These tests demonstrate that the weights in the map don't matter
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),hapDKmersHighSupport,1,3,Arrays.asList(hapA,hapB,hapC,hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),hapDKmersHighSupport,2,3,Arrays.asList(hapA,hapB,hapC,hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD),hapDKmersHighSupport,3,3,Arrays.asList(hapA,hapB,hapC,hapD)}, // Despite hapD having good support it is not weighted higher

                // Test of the output when only one hap has support
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD,hapF),hapDKmers,1,3,Arrays.asList(hapD)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD,hapF),hapDKmers,2,3,Arrays.asList(hapD,hapA, hapC)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD,hapF),hapDKmers,3,3,Arrays.asList(hapD,hapA,hapC)},
                new Object[]{Arrays.asList(hapA,hapB,hapC,hapD,hapF),hapDKmers,4,3,Arrays.asList(hapD,hapA,hapC,hapB,hapF)},
        };

        return tests;
    }

    // This test is intended to record the current behavior of the filtering code. This may be revised in the future.
    @Test (dataProvider = "filterPileupHaplotypesDataProvider")
    public void testFilterPileupHaplotypes(final List<Haplotype> inputHaplotypes,
                                           final Map<Kmer, Integer> kmerReadCounts,
                                           final int numPileupHaplotypes,
                                           final int kmerSize,
                                           final List<Haplotype> expected) {
        final Map<Kmer, MutableInt> counts = kmerReadCounts.entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey(), entry -> new MutableInt(entry.getValue())));
        Set<Haplotype> actual = AssemblyBasedCallerUtils.filterPileupHaplotypes(new HashSet<>(inputHaplotypes), counts, numPileupHaplotypes, kmerSize);

        Assert.assertEquals(actual, new HashSet<>(expected));
    }

    private static Event vcToEvent(final VariantContext vc) {
        Utils.validate(vc.getNAlleles() == 2, "must be biallelic");
        return new Event(vc.getContig(), vc.getStart(), vc.getReference(), vc.getAlternateAllele(0));
    }
}
