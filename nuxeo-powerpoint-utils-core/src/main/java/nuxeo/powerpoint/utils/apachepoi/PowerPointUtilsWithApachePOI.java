/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.powerpoint.utils.apachepoi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.platform.mimetype.MimetypeDetectionException;
import org.nuxeo.ecm.platform.mimetype.MimetypeNotFoundException;
import org.nuxeo.ecm.platform.mimetype.interfaces.MimetypeRegistry;
import org.nuxeo.ecm.platform.mimetype.service.MimetypeRegistryService;
import org.nuxeo.runtime.api.Framework;

import nuxeo.powerpoint.utils.api.PowerPointUtils;

/**
 * @since 10.10
 */
public class PowerPointUtilsWithApachePOI implements PowerPointUtils {

    public PowerPointUtilsWithApachePOI() {

    }

    // ==============================> SPLIT
    /*
     * IMPORTANT
     * As of today (Apache POI 4.1.1, January 2020), it is very very difficult to extract a slide. There is no API for
     * this. Getting the master, the layout, getting the related/embedded images, graphs etc. is super cumbersome, and
     * for once, the Internet is not helping. It confirms it's extremely hard to make sure you extract a slide with all
     * its dependencies (master, layouts, images, videos, ...) so we are doing it in a different way: basically, for
     * each slide, we delete all the other slides. This is surely a slow operation using CPU and i/o.
     */
    @Override
    public BlobList splitPresentation(Blob blob) throws IOException {

        BlobList result = new BlobList();
        String pptMimeType;
        String fileNameBase;

        if (blob == null) {
            return result;
        }

        pptMimeType = getBlobMimeType(blob);
        fileNameBase = blob.getFilename();
        fileNameBase = FilenameUtils.getBaseName(fileNameBase);
        fileNameBase = StringUtils.appendIfMissing(fileNameBase, "-");

        File originalFile = blob.getFile();

        try (XMLSlideShow ppt = new XMLSlideShow(blob.getStream())) {

            File tempDirectory = FileUtils.getTempDirectory();
            int slidesCount = ppt.getSlides().size();
            for (int i = 0; i < slidesCount; i++) {

                // 1. Duplicate the original presentation
                File newFile = new File(tempDirectory, fileNameBase + (i + 1) + ".pptx");
                FileUtils.copyFile(originalFile, newFile);

                // 2. Remove slides in the copy
                try (InputStream is = new FileInputStream(newFile)) {
                    try (XMLSlideShow copy = new XMLSlideShow(is)) {

                        for (int iBefore = 0; iBefore < i; iBefore++) {
                            copy.removeSlide(0);
                        }

                        // Now, our slide is the first one (0 based)
                        int tempSlidesCount = copy.getSlides().size();
                        for (int iAfter = 1; iAfter < tempSlidesCount; iAfter++) {
                            copy.removeSlide(1);
                        }

                        try (FileOutputStream out = new FileOutputStream(newFile)) {
                            copy.write(out);
                        }
                    }
                }

                // 3. Save as blob
                FileBlob oneSlidePres = new FileBlob(newFile, pptMimeType);
                result.add(oneSlidePres);
            }
        }

        return result;
    }

    /**
     * Returns a list of blobs, one/slide after splitting the presentation contained in the input document in the xpath
     * field (if null or empty, default to "file:content"). Returns an empty list in the blob at xpath is null, or is
     * not a presentation.
     * 
     * @param input, the document containing a PowerPoint presentation
     * @param xpath, the field storing the presentation. Optional, "file:content" by default
     * @return the list of blob, one/slide.
     * @since 10.10
     */
    public BlobList splitPresentation(DocumentModel input, String xpath) throws IOException {

        if (StringUtils.isBlank(xpath)) {
            xpath = "file:content";
        }
        Blob blob = (Blob) input.getPropertyValue(xpath);
        BlobList blobs = splitPresentation(blob);

        return blobs;
    }

    // ==============================> MERGE
    @Override
    public Blob mergeSlides(BlobList slides) {
        // TODO Auto-generated method stub
        // return null;
        throw new UnsupportedOperationException();
    }

    public Blob mergeSlides(DocumentModelList slides) {
        // TODO Auto-generated method stub
        // return null;
        throw new UnsupportedOperationException();
    }

    // ==============================> Utilities
    public Map<String, XSLFSlideMaster> getSlideMasters(XMLSlideShow slideShow) {

        HashMap<String, XSLFSlideMaster> namesAndMasters = new HashMap<String, XSLFSlideMaster>();
        for (XSLFSlideMaster master : slideShow.getSlideMasters()) {
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {

                namesAndMasters.put(layout.getName(), master);
            }
        }

        return namesAndMasters;

    }

    /**
     * @param blob
     * @since 10.10
     */
    public String getBlobMimeType(Blob blob) {

        if (blob == null) {
            throw new NullPointerException();
        }

        String mimeType = blob.getMimeType();
        if (StringUtils.isNotBlank(mimeType)) {
            return mimeType;
        }

        MimetypeRegistryService service = (MimetypeRegistryService) Framework.getService(MimetypeRegistry.class);
        try {
            mimeType = service.getMimetypeFromBlob(blob);
        } catch (MimetypeNotFoundException | MimetypeDetectionException e1) {
            try {
                mimeType = service.getMimetypeFromFile(blob.getFile());
            } catch (MimetypeNotFoundException | MimetypeDetectionException e2) {
                throw new NuxeoException("Cannot get a Mime Type from the blob or the file", e2);
            }
        }

        return mimeType;
    }

}
