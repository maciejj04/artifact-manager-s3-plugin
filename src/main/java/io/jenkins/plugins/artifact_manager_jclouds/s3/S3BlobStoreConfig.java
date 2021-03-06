/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.artifact_manager_jclouds.s3;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Failure;
import hudson.util.FormValidation;
import io.jenkins.plugins.artifact_manager_jclouds.JCloudsVirtualFile;
import io.jenkins.plugins.aws.global_configuration.AbstractAwsGlobalConfiguration;
import io.jenkins.plugins.aws.global_configuration.CredentialsAwsGlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;

import static hudson.Util.fixEmpty;

/**
 * Store the S3BlobStoreProvider configuration to save it on a separate file. This make that
 * the change of container does not affected to the Artifact functionality, you could change the container
 * and it would still work if both container contains the same data.
 */
@Symbol("s3")
@Extension
public class S3BlobStoreConfig extends AbstractAwsGlobalConfiguration {

    private static final String BUCKET_REGEXP = "^([a-z]|(\\d(?!\\d{0,2}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})))([a-z\\d]|(\\.(?!(\\.|-)))|(-(?!\\.))){1,61}[a-z\\d\\.]$";
    private static final Pattern bucketPattern = Pattern.compile(BUCKET_REGEXP);

    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_ARTIFACTS = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteArtifacts");
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean DELETE_STASHES = Boolean.getBoolean(S3BlobStoreConfig.class.getName() + ".deleteStashes");

    /**
     * Name of the S3 Bucket.
     */
    private String container;
    /**
     * Prefix to use for files, use to be a folder.
     */
    private String prefix;
    @Deprecated private transient String region, credentialsId;

    /**
     * field to fake S3 endpoint on test.
     */
    static AwsClientBuilder.EndpointConfiguration ENDPOINT;


    /**
     * class to test configuration against Amazon S3 Bucket.
     */
    private static class S3BlobStoreTester extends S3BlobStoreProvider {
        private static final long serialVersionUID = -3645770416235883487L;
        private transient S3BlobStoreConfig config;

        S3BlobStoreTester(String container, String prefix) {
            config = new S3BlobStoreConfig();
            config.setContainer(container);
            config.setPrefix(prefix);
        }

        @Override
        public S3BlobStoreConfig getConfiguration() {
            return config;
        }
    }

    public S3BlobStoreConfig() {
        load();
        if (fixEmpty(region) != null || fixEmpty(credentialsId) != null) {
            CredentialsAwsGlobalConfiguration.get().setRegion(region);
            CredentialsAwsGlobalConfiguration.get().setCredentialsId(credentialsId);
            region = null;
            credentialsId = null;
            save();
        }
    }

    public String getContainer() {
        return container;
    }

    @DataBoundSetter
    public void setContainer(String container) {
        this.container = container;
        checkValue(doCheckContainer(container));
        save();
    }

    public String getPrefix() {
        return prefix;
    }

    @DataBoundSetter
    public void setPrefix(String prefix){
        this.prefix = prefix;
        checkValue(doCheckPrefix(prefix));
        save();
    }

    private void checkValue(@NonNull FormValidation formValidation) {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new Failure(formValidation.getMessage());
        }
    }

    public boolean isDeleteArtifacts() {
        return DELETE_ARTIFACTS;
    }

    public boolean isDeleteStashes() {
        return DELETE_STASHES;
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Amazon S3 Bucket Access settings";
    }

    @Nonnull
    public static S3BlobStoreConfig get() {
        return ExtensionList.lookupSingleton(S3BlobStoreConfig.class);
    }

    /**
    *
    * @return an AmazonS3ClientBuilder using the region or not, it depends if a region is configured or not.
    */
    AmazonS3ClientBuilder getAmazonS3ClientBuilder() {
       AmazonS3ClientBuilder ret = AmazonS3ClientBuilder.standard();
       if(S3BlobStoreConfig.ENDPOINT != null){
           ret = ret.withPathStyleAccessEnabled(true).withEndpointConfiguration(S3BlobStoreConfig.ENDPOINT);
       } else if(StringUtils.isNotBlank(CredentialsAwsGlobalConfiguration.get().getRegion())) {
           ret = ret.withRegion(CredentialsAwsGlobalConfiguration.get().getRegion());
       } else {
           ret = ret.withForceGlobalBucketAccessEnabled(true);
       }
       return ret;
   }

    public FormValidation doCheckContainer(@QueryParameter String container){
        FormValidation ret = FormValidation.ok();
        if (StringUtils.isBlank(container)){
            ret = FormValidation.warning("The container name cannot be empty");
        } else if (!bucketPattern.matcher(container).matches()){
            ret = FormValidation.error("The S3 Bucket name does not match S3 bucket rules");
        }
        return ret;
    }

    public FormValidation doCheckPrefix(@QueryParameter String prefix){
        FormValidation ret;
        if (StringUtils.isBlank(prefix)) {
            ret = FormValidation.ok("Artifacts will be stored in the root folder of the S3 Bucket.");
        } else if (prefix.endsWith("/")) {
            ret = FormValidation.ok();
        } else {
            ret = FormValidation.error("A prefix must end with a slash.");
        }
        return ret;
    }

    /**
     * create an S3 Bucket.
     * @param name name of the S3 Bucket.
     * @return return the Bucket created.
     * @throws IOException in case of error obtaining the credentials, in other kind of errors it will throw the
     * runtime exceptions are thrown by createBucket method.
     */
    public Bucket createS3Bucket(String name) throws IOException {
        AmazonS3ClientBuilder builder = getAmazonS3ClientBuilder();
        AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(
                CredentialsAwsGlobalConfiguration.get().sessionCredentials(builder));
        AmazonS3 client = builder.withCredentials(credentialsProvider).build();
        return client.createBucket(name);
    }

    @RequirePOST
    public FormValidation doCreateS3Bucket(@QueryParameter String container, @QueryParameter String prefix) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            createS3Bucket(container);
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        return ret;
    }

    void checkGetBucketLocation(String container) throws IOException {
        AmazonS3ClientBuilder builder = getAmazonS3ClientBuilder();
        AWSStaticCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(
                CredentialsAwsGlobalConfiguration.get().sessionCredentials(builder));
        AmazonS3 client = builder.withCredentials(credentialsProvider).build();
        client.getBucketLocation(container);
    }

    @RequirePOST
    public FormValidation doValidateS3BucketConfig(@QueryParameter String container, @QueryParameter String prefix) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        FormValidation ret = FormValidation.ok("success");
        try {
            S3BlobStoreProvider provider = new S3BlobStoreTester(container, prefix);
            JCloudsVirtualFile jc = new JCloudsVirtualFile(provider, container, prefix);
            jc.list();
        } catch (Throwable t){
            String msg = processExceptionMessage(t);
            ret = FormValidation.error(StringUtils.abbreviate(msg, 200));
        }
        try {
            checkGetBucketLocation(container);
        } catch (SdkClientException e) {
            if (e instanceof AmazonServiceException) {
                ret = FormValidation.warning(e, "Errors occurred in Amazon S3 while processing the request");
                return ret;
            }
            ret = FormValidation.warning(e, "Error encountered in the client while making the request or handling the response.");
        }
        catch (Throwable t){
            ret = FormValidation.warning(t, "GetBucketLocation failed");
        }
        return ret;
    }

}
