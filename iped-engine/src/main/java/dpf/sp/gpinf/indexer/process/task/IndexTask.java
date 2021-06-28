package dpf.sp.gpinf.indexer.process.task;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.CmdLineArgs;
import dpf.sp.gpinf.indexer.WorkerProvider;
import dpf.sp.gpinf.indexer.config.ConfigurationManager;
import dpf.sp.gpinf.indexer.config.IndexTaskConfig;
import dpf.sp.gpinf.indexer.io.ParsingReader;
import dpf.sp.gpinf.indexer.parsers.IndexerDefaultParser;
import dpf.sp.gpinf.indexer.process.IndexItem;
import dpf.sp.gpinf.indexer.process.Worker.STATE;
import dpf.sp.gpinf.indexer.search.IPEDSource;
import dpf.sp.gpinf.indexer.util.CloseFilterReader;
import dpf.sp.gpinf.indexer.util.FragmentingReader;
import dpf.sp.gpinf.indexer.util.IOUtil;
import dpf.sp.gpinf.indexer.util.IPEDException;
import dpf.sp.gpinf.indexer.util.Util;
import gpinf.dev.data.Item;
import iped3.IItem;
import iped3.sleuthkit.ISleuthKitItem;
import macee.core.Configurable;

/**
 * Tarefa de indexação dos itens. Indexa apenas as propriedades, caso a
 * indexação do conteúdo esteja desabilitada. Reaproveita o texto dos itens caso
 * tenha sido extraído por tarefas anteriores.
 *
 * Indexa itens grandes dividindo-os em fragmentos, pois a lib de indexação
 * consome mta memória com documentos grandes.
 *
 */
public class IndexTask extends AbstractTask {

    private static Logger LOGGER = LoggerFactory.getLogger(IndexTask.class);
    private static String TEXT_SIZES = IndexTask.class.getSimpleName() + "TEXT_SIZES"; //$NON-NLS-1$
    public static final String TEXT_SPLITTED = "textSplitted";
    public static final String extraAttrFilename = "extraAttributes.dat"; //$NON-NLS-1$

    private IndexerDefaultParser autoParser;
    private List<IdLenPair> textSizes;

    private IndexTaskConfig indexConfig;

    public static class IdLenPair {

        int id;
        long length;

        public IdLenPair(int id, long len) {
            this.id = id;
            this.length = len;
        }
    }

    public static boolean isTreeNodeOnly(IItem item) {
        return (!item.isToAddToCase() && (item.isDir() || item.isRoot() || item.hasChildren()))
                || item.getExtraAttribute(IndexItem.TREENODE) != null;
    }

    public static void configureTreeNodeAttributes(IItem item) {
        if (item.isSubItem()) {
            item.dispose();
        }
        if (item instanceof ISleuthKitItem) {
            ((ISleuthKitItem) item).setSleuthId(null);
        }
        item.setFile(null);
        item.setExportedFile(null);
        item.setIdInDataSource(null);
        item.setInputStreamFactory(null);
        item.setExtraAttribute(IndexItem.TREENODE, "true"); //$NON-NLS-1$
        item.getCategorySet().clear();
    }

    public void process(IItem evidence) throws IOException {
        if (evidence.isQueueEnd()) {
            return;
        }

        if (SkipCommitedTask.isAlreadyCommited(evidence)) {
            evidence.setToIgnore(true);
            return;
        }

        Reader textReader = null;

        if (!evidence.isToAddToCase()) {
            if (isTreeNodeOnly(evidence)) {
                configureTreeNodeAttributes(evidence);
                textReader = new StringReader("");
            } else
                return;
        }

        stats.updateLastId(evidence.getId());

        if (textReader == null) {
            if (indexConfig.isIndexFileContents() && (indexConfig.isIndexUnallocated()
                    || !BaseCarveTask.UNALLOCATED_MIMETYPE.equals(evidence.getMediaType()))) {
                textReader = evidence.getTextReader();
                if (textReader == null) {
                    LOGGER.warn("Null Text reader, creating a new one for {}", evidence.getPath()); //$NON-NLS-1$
                    try {
                        TikaInputStream tis = evidence.getTikaStream();
                        Metadata metadata = getMetadata(evidence);
                        final ParseContext context = getTikaContext(evidence);
                        textReader = new ParsingReader(this.autoParser, tis, metadata, context);
                        ((ParsingReader) textReader).startBackgroundParsing();

                    } catch (IOException e) {
                        LOGGER.warn("{} Error opening: {} {}", Thread.currentThread().getName(), evidence.getPath(), //$NON-NLS-1$
                                e.toString());
                    }
                }
            }
        }

        if (textReader == null)
            textReader = new StringReader(""); //$NON-NLS-1$

        FragmentingReader fragReader = new FragmentingReader(textReader, indexConfig.getTextSplitSize(),
                indexConfig.getTextOverlapSize());
        CloseFilterReader noCloseReader = new CloseFilterReader(fragReader);

        int fragments = fragReader.estimateNumberOfFrags();
        if (fragments == -1) {
            fragments = 1;
        }
        String origPersistentId = Util.getPersistentId(evidence);
        try {
            /**
             * breaks very large texts in separate documents to be indexed
             */
            do {
                // use fragName = 1 for all frags, except last, to check if last frag was
                // indexed
                // and to reuse same frag id when continuing an aborted processing
                int fragName = (--fragments) == 0 ? 0 : 1;

                String fragPersistId = Util.generatePersistentIdForTextFrag(origPersistentId, fragName);
                evidence.setExtraAttribute(IndexItem.PERSISTENT_ID, fragPersistId);

                if (fragments != 0) {
                    stats.incSplits();
                    evidence.setExtraAttribute(TEXT_SPLITTED, Boolean.TRUE.toString());
                    LOGGER.info("{} Splitting text of {}", Thread.currentThread().getName(), evidence.getPath()); //$NON-NLS-1$
                }

                Document doc = IndexItem.Document(evidence, noCloseReader, output);
                addDocumentSafe(doc, evidence);

                while (worker.state != STATE.RUNNING) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

            } while (!Thread.currentThread().isInterrupted() && fragReader.nextFragment());

        } catch (IOException e) {
            if (IOUtil.isDiskFull(e))
                throw new IPEDException(
                        "Not enough space for the index on " + worker.manager.getIndexTemp().getAbsolutePath()); //$NON-NLS-1$
            else
                throw e;
        } finally {
            evidence.setExtraAttribute(IndexItem.PERSISTENT_ID, origPersistentId);
            noCloseReader.reallyClose();
        }

        textSizes.add(new IdLenPair(evidence.getId(), fragReader.getTotalTextSize()));

    }

    private void addDocumentSafe(Document doc, IItem evidence) throws IOException {
        // loop to handle possible corrupted metadata, see #571
        while (true) {
            try {
                worker.writer.addDocument(doc);
                break;
            } catch (IllegalArgumentException e) {
                String msg = e.toString();
                if (msg.contains("cannot change DocValues type from")) {
                    String badField = msg.substring(msg.indexOf('\"') + 1, msg.length() - 1);
                    String badValue = doc.get(badField);
                    LOGGER.warn("Possible corrupted metadata value '{}' in field '{}' in item '{}' ({} bytes)",
                            badValue, badField, evidence.getPath(), evidence.getLength());
                    // removes default field
                    doc.removeField(badField);
                    // removes docValues field
                    doc.removeField(badField);
                    // add the value in other similar field as a generic string
                    badField += ":string";
                    doc.add(new TextField(badField, badValue, Field.Store.YES));
                    doc.add(new SortedSetDocValuesField(badField, new BytesRef(IndexItem.normalize(badValue, true))));
                } else {
                    throw e;
                }
            }
        }
    }

    private Metadata getMetadata(IItem evidence) {
        // new metadata to prevent ConcurrentModificationException while indexing
        Metadata metadata = new Metadata();
        ParsingTask.fillMetadata(evidence, metadata);
        return metadata;
    }

    private ParseContext getTikaContext(IItem evidence) {
        ParsingTask pt = new ParsingTask(evidence, this.autoParser);
        pt.setWorker(worker);
        pt.init(ConfigurationManager.get());
        ParseContext context = pt.getTikaContext();
        // this is to not create new items while indexing
        pt.setExtractEmbedded(false);
        return context;
    }

    @Override
    public List<Configurable<?>> getConfigurables() {
        return Arrays.asList(new IndexTaskConfig());
    }

    @Override
    public void init(ConfigurationManager configurationManager) throws Exception {
        
        indexConfig = configurationManager.findObject(IndexTaskConfig.class);

        CmdLineArgs args = (CmdLineArgs) caseData.getCaseObject(CmdLineArgs.class.getName());
        if (args.isAppendIndex() || args.isContinue() || args.isRestart()) {
            try (IPEDSource ipedSrc = new IPEDSource(output.getParentFile(), worker.writer)) {
                stats.setLastId(ipedSrc.getLastId());
                Item.setStartID(ipedSrc.getLastId() + 1);
            }
        }

        textSizes = (List<IdLenPair>) caseData.getCaseObject(TEXT_SIZES);
        if (textSizes == null) {
            textSizes = Collections.synchronizedList(new ArrayList<IdLenPair>());
            caseData.putCaseObject(TEXT_SIZES, textSizes);

            File prevFile = new File(output, "data/texts.size"); //$NON-NLS-1$
            if (prevFile.exists()) {
                FileInputStream fileIn = new FileInputStream(prevFile);
                ObjectInputStream in = new ObjectInputStream(fileIn);

                long[] textSizesArray;
                Object array = (long[]) in.readObject();
                if (array instanceof long[])
                    textSizesArray = (long[]) array;
                else {
                    int i = 0;
                    textSizesArray = new long[((int[]) array).length];
                    for (int size : (int[]) array)
                        textSizesArray[i++] = size * 1000L;
                }
                for (int i = 0; i < textSizesArray.length; i++) {
                    if (textSizesArray[i] != 0 && i <= stats.getLastId()) {
                        textSizes.add(new IdLenPair(i, textSizesArray[i]));
                    }
                }
                in.close();
                fileIn.close();
            }
        }

        IndexItem.loadMetadataTypes(new File(output, "conf")); //$NON-NLS-1$
        loadExtraAttributes();

        this.autoParser = new IndexerDefaultParser();

    }

    @SuppressWarnings("unchecked")
    @Override
    public void finish() throws Exception {

        textSizes = (List<IdLenPair>) caseData.getCaseObject(TEXT_SIZES);
        if (textSizes != null) {
            salvarTamanhoTextosExtraidos();

            saveExtraAttributes(output);

            IndexItem.saveMetadataTypes(new File(output, "conf")); //$NON-NLS-1$
        }
        caseData.putCaseObject(TEXT_SIZES, null);

    }

    public static void saveExtraAttributes(File output) throws IOException {
        File extraAttributtesFile = new File(output, "data/" + extraAttrFilename); //$NON-NLS-1$
        Set<String> extraAttr = Item.getAllExtraAttributes();
        Util.writeObject(extraAttr, extraAttributtesFile.getAbsolutePath());
        Util.fsync(extraAttributtesFile.toPath());
    }

    private void loadExtraAttributes() throws ClassNotFoundException, IOException {

        File extraAttributtesFile = new File(output, "data/" + extraAttrFilename); //$NON-NLS-1$
        if (extraAttributtesFile.exists()) {
            Set<String> extraAttributes = (Set<String>) Util.readObject(extraAttributtesFile.getAbsolutePath());
            Item.getAllExtraAttributes().addAll(extraAttributes);
        }
    }

    private void salvarTamanhoTextosExtraidos() throws Exception {
        WorkerProvider.getInstance().firePropertyChange("mensagem", "", "Saving extracted text sizes..."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LOGGER.info("Saving extracted text sizes..."); //$NON-NLS-1$

        long[] textSizesArray = new long[stats.getLastId() + 1];

        for (int i = 0; i < textSizes.size(); i++) {
            IdLenPair pair = textSizes.get(i);
            textSizesArray[pair.id] = pair.length;
        }

        Util.writeObject(textSizesArray, output.getAbsolutePath() + "/data/texts.size"); //$NON-NLS-1$
    }

}
