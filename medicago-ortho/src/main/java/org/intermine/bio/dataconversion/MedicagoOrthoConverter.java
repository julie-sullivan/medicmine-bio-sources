package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2002-2013 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.collections.keyvalue.MultiKey;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.tools.ant.BuildException;
import org.intermine.bio.util.OrganismData;
import org.intermine.bio.util.OrganismRepository;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.util.FormattedTextParser;
import org.intermine.metadata.StringUtil;
import org.intermine.xml.full.Item;

/**
 * @author Julie Sullivan
 */
public class MedicagoOrthoConverter extends BioFileConverter
{
    private Properties props = new Properties();
    private static final String PROP_FILE = "medicago-ortho_config.properties";
    private static final String DATASET_TITLE = "Medicago orthologs";
    private static final String DATA_SOURCE_NAME = "MTGD";
    private static final Logger LOG = Logger.getLogger(MedicagoOrthoConverter.class);
    private Set<String> taxonIds = new HashSet<String>();
    private Set<String> homologues = new HashSet<String>();
    private Map<MultiKey, String> identifiersToGenes = new HashMap<MultiKey, String>();
    private Map<String, String> config = new HashMap<String, String>();
    private static String evidenceRefId = null;
    private static final Map<String, String> TYPES = new HashMap<String, String>();
    private static final String DEFAULT_IDENTIFIER_TYPE = "primaryIdentifier";
    private OrganismRepository or;
    private Set<String> databasesNamesToPrepend = new HashSet<String>();
    private Map<String, Map<String, String>> geneIdPolymorphism =
            new HashMap<String, Map<String, String>>();

    private static final String EVIDENCE_CODE_ABBR = "AA";
    private static final String EVIDENCE_CODE_NAME = "Amino acid sequence comparison";
    // PANTHER publication pubmed ids, refer to http://www.pantherdb.org/publications.jsp
    private static ArrayList<String> PUBLICATIONS = new ArrayList<String>() {
        private static final long serialVersionUID = 1L;
    {
        add("12520017");
        add("20015972");
        add("16912992");
        add("19597783");
        add("20534164");
        add("15492219");
    }};

    private IdResolver rslv;

    /**
     * Constructor
     * @param writer the ItemWriter used to handle the resultant items
     * @param model the Model
     * @throws ObjectStoreException can't store dataset
     */
    public MedicagoOrthoConverter(ItemWriter writer, Model model)
        throws ObjectStoreException {
        super(writer, model, DATA_SOURCE_NAME, DATASET_TITLE);
        readConfig();
        or = OrganismRepository.getOrganismRepository();
    }

    static {
        TYPES.put("LDO", "least diverged orthologue");
        TYPES.put("O", "orthologue");
        TYPES.put("P", "paralogue");
	TYPES.put("X", "homologue");
	TYPES.put("LDX", "least diverged homologue");
    }

    /**
     * Sets the list of taxonIds that should be processed.  All genes will be loaded.
     *
     * @param taxonIds a space-separated list of taxonIds
     */
    public void setPantherOrganisms(String taxonIds) {
        this.taxonIds = new HashSet<String>(Arrays.asList(StringUtil.split(taxonIds, " ")));
        LOG.info("Setting list of organisms to " + taxonIds);
    }

    /**
     * Sets the list of taxonIds of homologues that should be processed.  These homologues will only
     * be processed if they are homologues for the organisms of interest.
     *
     * @param homologues a space-separated list of taxonIds
     */
    public void setPantherHomologues(String homologues) {
        this.homologues = new HashSet<String>(Arrays.asList(StringUtil.split(homologues, " ")));
        LOG.info("Setting list of homologues to " + homologues);
    }

    private void readConfig() {
        try {
            props.load(getClass().getClassLoader().getResourceAsStream(PROP_FILE));
        } catch (IOException e) {
            throw new RuntimeException("Problem loading properties '" + PROP_FILE + "'", e);
        }

        for (Map.Entry<Object, Object> entry: props.entrySet()) {
            String key = (String) entry.getKey();
            String value = ((String) entry.getValue()).trim();

            if ("prependDBName".equals(key)) {
                String[] dbnames = value.split(",");
                for (String name : dbnames) {
                    databasesNamesToPrepend.add(name);
                }
                continue;
            }

            if (key.contains("geneid.polymorphism")) {
                String[] attributes = key.split("\\.");
                if (attributes.length == 4) {
                    String taxonId = attributes[0];
                    if (geneIdPolymorphism.isEmpty() || geneIdPolymorphism.get(taxonId).isEmpty()) {
                        Map<String, String> patternMap = new HashMap<String, String>();
                        patternMap.put(attributes[3].trim(), value);
                        geneIdPolymorphism.put(taxonId, patternMap);
                    } else {
                        geneIdPolymorphism.get(taxonId).put(attributes[3].trim(), value);
                    }
                }
                continue;
            }

            String[] attributes = key.split("\\.");
            if (attributes.length == 0) {
                throw new RuntimeException("Problem loading properties '" + PROP_FILE + "' on line "
                                           + key);
            }
            String taxonId = attributes[0];
            config.put(taxonId, value);
        }
    }

    private String getGene(String geneId, String taxonId)
        throws ObjectStoreException {
        String identifierType = config.get(taxonId);
        if (StringUtils.isEmpty(identifierType)) {
            identifierType = DEFAULT_IDENTIFIER_TYPE;
        }

        geneId = parseIdentifier(geneId);
        String resolvedGenePid = resolveGene(taxonId, geneId);
        if (resolvedGenePid == null) {
            return null;
        }

        String refId = identifiersToGenes.get(new MultiKey(taxonId, resolvedGenePid));
        if (refId == null) {
            Item gene = createItem("Gene");
            gene.setAttribute(DEFAULT_IDENTIFIER_TYPE, resolvedGenePid);

            if (geneIdPolymorphism.containsKey(taxonId)) {
                Map<String, String> patternMap = geneIdPolymorphism.get(taxonId);
                for (String key : patternMap.keySet()) {
                    if (geneId.startsWith(key)) {
                        identifierType = patternMap.get(key);
                        if (!identifierType.equals(DEFAULT_IDENTIFIER_TYPE)) {
                            if ("crossReferences".equals(identifierType)) {
                                gene.addToCollection(identifierType,
                                        createCrossReference(gene.getIdentifier(), geneId,
                                                DATA_SOURCE_NAME, true));
                            } else {
                                gene.setAttribute(identifierType, geneId);
                            }
                        }
                    }
                }
            } else {
                if (!identifierType.equals(DEFAULT_IDENTIFIER_TYPE)) {
                    if ("crossReferences".equals(identifierType)) {
                        gene.addToCollection(identifierType,
                                createCrossReference(gene.getIdentifier(), geneId,
                                        DATA_SOURCE_NAME, true));
                    } else {
                        gene.setAttribute(identifierType, geneId);
                    }
                }
            }

            gene.setReference("organism", getOrganism(taxonId));
            refId = gene.getIdentifier();
            identifiersToGenes.put(new MultiKey(taxonId, resolvedGenePid), refId);
            store(gene);
        }
        return refId;
    }

    private String parseIdentifier(String ident) {
        String[] identifierString = ident.split("=");
        String dbName = identifierString[0];
        String identifier = identifierString[identifierString.length-1];
        if (databasesNamesToPrepend.contains(dbName)) {
            identifier = dbName + ":" + identifier;
        }
        return identifier;
    }

    /**
     * Process the text file
     * @param reader the Reader
     * @throws Exception if something goes wrong
     */
    @Override
    public void process(Reader reader) throws Exception {
        if (taxonIds.isEmpty()) {
            LOG.warn("medicagoortho.organisms property not set in project XML file");
        }
        if (homologues.isEmpty()) {
            LOG.warn("medicagoortho.homologues property not set in project XML file");
        }

        //Create id resolver
        Set<String> allTaxonIds = new HashSet<String>() {
            private static final long serialVersionUID = 1L;
            {
                addAll(taxonIds);
                addAll(homologues);
            }
        };
        if (rslv == null) {
            rslv = IdResolverService.getIdResolverByOrganism(allTaxonIds);
        }

        Iterator<String[]> lineIter = FormattedTextParser.parseTabDelimitedReader(reader);
        while (lineIter.hasNext()) {
            String[] bits = lineIter.next();
            if (bits.length < 5) {
                continue;
            }

            String[] gene1IdentifierString = bits[0].split("\\|");
            String[] gene2IdentifierString = bits[1].split("\\|");

            if (StringUtils.isEmpty(gene1IdentifierString[0])
                    || StringUtils.isEmpty(gene2IdentifierString[0])) {
                // blank line

                continue;
            }

            String taxonId1 = getTaxon(gene1IdentifierString[0]);
            String taxonId2 = getTaxon(gene2IdentifierString[0]);
            if (!isValid(taxonId1, taxonId2)) {
                // not an organism of interest, skip
                continue;
            }
            String type = bits[2];
            String pantherId = bits[4];

            String gene1 = getGene(gene1IdentifierString[1], taxonId1);
            String gene2 = getGene(gene2IdentifierString[1], taxonId2);

            processHomologues(gene1, gene2, type, pantherId);
            processHomologues(gene2, gene1, type, pantherId);
        }
    }

    private void processHomologues(String gene1, String gene2, String type, String pantherId)
            throws ObjectStoreException {
            if (gene1 == null || gene2 == null) {
                return;
            }
            Item homologue = createItem("Homologue");
            homologue.setReference("gene", gene1);
            homologue.setReference("homologue", gene2);
            homologue.addToCollection("evidence", getEvidence());
            homologue.setAttribute("type", TYPES.get(type));
            homologue.addToCollection("crossReferences",
                createCrossReference(homologue.getIdentifier(), pantherId,
                        DATA_SOURCE_NAME, true));
            store(homologue);
        }

    // genes (in taxonIDs) are always processed
    // homologues are only processed if they are of an organism of interest
    private boolean isValid(String organism1, String organism2) {
        if (taxonIds.isEmpty()) {
            // no config so process everything
            return true;
        }
        if (taxonIds.contains(organism1) && taxonIds.contains(organism2)) {
            // both are organisms of interest
            return true;
        }
        if (homologues.isEmpty()) {
            // only interested in homologues of interest, so at least one of this pair isn't valid
            return false;
        }
        // one gene is from an organism of interest
        // one homologue is from an organism we want
        if (taxonIds.contains(organism1) && homologues.contains(organism2)) {
            return true;
        }
        if (homologues.contains(organism1) && taxonIds.contains(organism2)) {
            return true;
        }
        return false;
    }

    private String getTaxon(String name) {
        OrganismData od = or.getOrganismDataByUniprot(name);
        if (od == null) {
            throw new BuildException("No data for `" + name + "`.  Please add to repository.");
        }
        //int taxonId = od.getTaxonId();
        //String taxonIdString = String.valueOf(taxonId);
        //return taxonIdString;
        return od.getTaxonId();
    }

    private String getEvidence()
        throws ObjectStoreException {

        if (evidenceRefId == null) {
            Item eviCode = createItem("OrthologueEvidenceCode");
            eviCode.setAttribute("abbreviation", EVIDENCE_CODE_ABBR);
            eviCode.setAttribute("name", EVIDENCE_CODE_NAME);
            try {
                store(eviCode);
            } catch (ObjectStoreException e) {
                throw new ObjectStoreException(e);
            }
            String eviCodeRefId = eviCode.getIdentifier();

            List<String> pubRefIds = new ArrayList<String>();
            for (String pubmed : PUBLICATIONS) {
                Item pub = createItem("Publication");
                pub.setAttribute("pubMedId", pubmed);
                String pubRefId = pub.getIdentifier();
                pubRefIds.add(pubRefId);
                try {
                    store(pub);
                } catch (ObjectStoreException e) {
                    throw new ObjectStoreException(e);
                }
            }

            Item evidence = createItem("OrthologueEvidence");
            evidence.setReference("evidenceCode", eviCodeRefId);
            evidence.setCollection("publications", pubRefIds);
            try {
                store(evidence);
            } catch (ObjectStoreException e) {
                throw new ObjectStoreException(e);
            }

            evidenceRefId = evidence.getIdentifier();
        }
        return evidenceRefId;
    }

    private String resolveGene(String taxonId, String identifier) {
        // Human - Ensembl as pid
        if ("9606".equals(taxonId)) {
            return identifier;
        }
        if("3702".equals(taxonId)) {
            return identifier.toUpperCase();
        }
        if("3880".equals(taxonId)) {
            return identifier;
        }

        if (rslv == null || !rslv.hasTaxon(taxonId)) {
            // no id resolver available, so return the original identifier
            return identifier;
        }
        int resCount = rslv.countResolutions(taxonId, identifier);
        if (resCount != 1) {
            LOG.info("RESOLVER: failed to resolve gene to one identifier, ignoring gene: "
                     + identifier + " count: " + resCount + " Resolved: "
                     + rslv.resolveId(taxonId, identifier));
            return null;
        }
        return rslv.resolveId(taxonId, identifier).iterator().next();
    }
}
