package buaa.rag.service;

import buaa.rag.model.TextSegment;
import buaa.rag.repository.TextSegmentRepository;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理服务
 * 负责文档解析、文本提取和智能分块
 */
@Service
public class DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

    @Autowired
    private TextSegmentRepository segmentRepository;

    @Value("${file.parsing.chunk-size}")
    private int maxChunkSize;

    /**
     * 解析文档并保存分块
     * 
     * @param documentMd5 文档MD5哈希
     * @param inputStream 文档输入流
     * @throws IOException 文件读取错误
     * @throws TikaException 解析错误
     */
    public void processAndStore(String documentMd5, InputStream inputStream) 
            throws IOException, TikaException {
        log.info("开始处理文档: {}", documentMd5);

        try {
            // 步骤1: 提取文本
            String extractedText = performTextExtraction(inputStream);
            log.info("文本提取成功，字符数: {}", extractedText.length());

            // 步骤2: 智能分块
            List<String> textChunks = performIntelligentSegmentation(extractedText);
            log.info("文本分块完成，片段数: {}", textChunks.size());

            // 步骤3: 持久化
            persistTextSegments(documentMd5, textChunks);
            log.info("文档处理完成: {}, 总片段: {}", documentMd5, textChunks.size());

        } catch (SAXException e) {
            log.error("文档解析失败: {}", documentMd5, e);
            throw new RuntimeException("文档解析错误", e);
        }
    }

    /**
     * 删除指定文档的全部分块。
     *
     * @param documentMd5 文档MD5哈希
     */
    public void deleteSegments(String documentMd5) {
        if (documentMd5 == null || documentMd5.isBlank()) {
            return;
        }
        segmentRepository.deleteByDocumentMd5(documentMd5);
        log.info("已删除文档分块: {}", documentMd5);
    }

    /**
     * 使用Apache Tika提取文本
     */
    private String performTextExtraction(InputStream stream) 
            throws IOException, TikaException, SAXException {
        BodyContentHandler contentHandler = new BodyContentHandler(-1);
        Metadata documentMetadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        AutoDetectParser documentParser = new AutoDetectParser();

        documentParser.parse(stream, contentHandler, documentMetadata, parseContext);
        return contentHandler.toString();
    }

    /**
     * 持久化文本片段
     */
    private void persistTextSegments(String documentMd5, List<String> chunks) {
        int index = 1;
        for (String chunkText : chunks) {
            TextSegment segment = new TextSegment();
            segment.setDocumentMd5(documentMd5);
            segment.setFragmentIndex(index);
            segment.setTextData(chunkText);
            segmentRepository.save(segment);
            index++;
        }
        log.info("已保存 {} 个文本片段", chunks.size());
    }

    /**
     * 智能文本分段
     * 采用多级分割策略保持语义完整性
     */
    private List<String> performIntelligentSegmentation(String fullText) {
        List<String> resultChunks = new ArrayList<>();
        String[] paragraphs = splitIntoParagraphs(fullText);
        StringBuilder chunkBuilder = new StringBuilder();

        for (String paragraph : paragraphs) {
            // 场景1: 段落超长，需要细分
            if (paragraph.length() > maxChunkSize) {
                if (chunkBuilder.length() > 0) {
                    resultChunks.add(chunkBuilder.toString().trim());
                    chunkBuilder.setLength(0);
                }
                resultChunks.addAll(subdivideOverlongParagraph(paragraph));
            }
            // 场景2: 添加会超出限制
            else if (chunkBuilder.length() + paragraph.length() + 2 > maxChunkSize) {
                if (chunkBuilder.length() > 0) {
                    resultChunks.add(chunkBuilder.toString().trim());
                }
                chunkBuilder = new StringBuilder(paragraph);
            }
            // 场景3: 正常添加
            else {
                if (chunkBuilder.length() > 0) {
                    chunkBuilder.append("\n\n");
                }
                chunkBuilder.append(paragraph);
            }
        }

        // 添加最后的片段
        if (chunkBuilder.length() > 0) {
            resultChunks.add(chunkBuilder.toString().trim());
        }

        return resultChunks;
    }

    /**
     * 按段落分割
     */
    private String[] splitIntoParagraphs(String text) {
        return text.split("\n\n+");
    }

    /**
     * 细分超长段落
     */
    private List<String> subdivideOverlongParagraph(String paragraph) {
        List<String> subChunks = new ArrayList<>();
        String[] sentences = splitIntoSentences(paragraph);
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            // 单句超长，需要词级分割
            if (sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    subChunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                subChunks.addAll(subdivideOverlongSentence(sentence));
            }
            // 添加会超限
            else if (currentChunk.length() + sentence.length() > maxChunkSize) {
                if (currentChunk.length() > 0) {
                    subChunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(sentence);
            }
            // 正常添加
            else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            subChunks.add(currentChunk.toString().trim());
        }

        return subChunks;
    }

    /**
     * 按句子分割
     */
    private String[] splitIntoSentences(String paragraph) {
        return paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");
    }

    /**
     * 使用HanLP细分超长句子
     * 保持中文语义完整性
     */
    private List<String> subdivideOverlongSentence(String sentence) {
        try {
            List<String> wordChunks = new ArrayList<>();
            List<Term> terms = StandardTokenizer.segment(sentence);
            StringBuilder wordBuilder = new StringBuilder();

            for (Term term : terms) {
                String word = term.word;
                
                if (wordBuilder.length() + word.length() > maxChunkSize && wordBuilder.length() > 0) {
                    wordChunks.add(wordBuilder.toString());
                    wordBuilder.setLength(0);
                }
                wordBuilder.append(word);
            }

            if (wordBuilder.length() > 0) {
                wordChunks.add(wordBuilder.toString());
            }

            log.debug("HanLP分词 - 原句: {} 字符, 分词: {} 个, 片段: {} 个", 
                     sentence.length(), terms.size(), wordChunks.size());
            return wordChunks;

        } catch (Exception e) {
            log.warn("HanLP分词失败，回退到字符分割: {}", e.getMessage());
            return fallbackCharacterSplit(sentence);
        }
    }

    /**
     * 备用方案：按字符分割
     */
    private List<String> fallbackCharacterSplit(String text) {
        List<String> chunks = new ArrayList<>();
        
        int position = 0;
        while (position < text.length()) {
            int endPosition = Math.min(position + maxChunkSize, text.length());
            chunks.add(text.substring(position, endPosition));
            position = endPosition;
        }
        
        return chunks;
    }
}
