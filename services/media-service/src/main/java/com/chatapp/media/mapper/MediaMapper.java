package com.chatapp.media.mapper;

import com.chatapp.media.dto.response.MediaResponse;
import com.chatapp.media.entity.MediaFile;
import com.chatapp.media.entity.MediaMetadata;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface MediaMapper {

    @Mapping(target = "id", source = "file.id")
    @Mapping(target = "kind", source = "file.kind")
    @Mapping(target = "fileName", source = "file.originalName")
    @Mapping(target = "mimeType", source = "file.mimeType")
    @Mapping(target = "sizeBytes", source = "file.sizeBytes")
    @Mapping(target = "width", source = "metadata.width")
    @Mapping(target = "height", source = "metadata.height")
    @Mapping(target = "durationSeconds", source = "metadata.durationSeconds")
    @Mapping(target = "hasThumbnail", expression = "java(metadata.hasThumbnail())")
    @Mapping(target = "createdAt", source = "file.createdAt")
    MediaResponse toResponse(MediaFile file, MediaMetadata metadata);
}
