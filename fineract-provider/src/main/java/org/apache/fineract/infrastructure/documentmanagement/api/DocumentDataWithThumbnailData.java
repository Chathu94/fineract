package org.apache.fineract.infrastructure.documentmanagement.api;

import org.apache.fineract.infrastructure.documentmanagement.data.DocumentData;

public class DocumentDataWithThumbnailData extends DocumentData {
    private String thumbnailData;

    public DocumentDataWithThumbnailData(Long id, String parentEntityType, Long parentEntityId, String name, String fileName, Long size, String type, String description, String location, Integer storageType, String thumbnailData) {
        super(id, parentEntityType, parentEntityId, name, fileName, size, type, description, location, storageType);
        this.thumbnailData = thumbnailData;
    }

    public String getThumbnailData() {
        return thumbnailData;
    }

    public void setThumbnailData(String thumbnailData) {
        this.thumbnailData = thumbnailData;
    }
}
