package buaa.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索元数据过滤条件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetadataFilter {

    private String department;
    private String docType;
    private String policyYear;
    private List<String> tags;

    public boolean isEmpty() {
        return isBlank(department) && isBlank(docType) && isBlank(policyYear)
            && (tags == null || tags.isEmpty());
    }

    public List<String> normalizedTags() {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
