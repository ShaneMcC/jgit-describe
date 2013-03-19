package org.mdonoughe;

import java.io.File;

import java.util.regex.Pattern;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.FollowFilter;

/**
 * Ant task to emulate git-describe using jgit.
 */
public class JGitDescribeTask extends Task {

    /** Path to .git Directory. */
    private File dir;

    /** Length of sha1 to use in output. */
    private int shalength;

    /** What reference to use as a starting point for the walk. */
    private String ref;

    /** What property to store the output in. */
    private String property;

    /** Check specifically for the last commit in this subdir. */
    private String subdir;

    /**
     * Set the .git directory
     *
     * @param path
     */
    public void setDir(final File path) {
        dir = path;
    }

    /**
     * Set the sha1 length
     *
     * @param length New value
     */
    public void setShalength(final int length) {
        shalength = length;
    }

    /**
     * Set the reference to use as a starting point for the walk.
     *
     * @param newRef New value
     */
    public void setRef(final String newRef) {
        ref = newRef;
    }

    /**
     * Set the property to store the output in
     *
     * @param oproperty New value
     */
    public void setProperty(final String oproperty) {
        property = oproperty;
    }

    /**
     * Set the subdir to look at
     *
     * @param newSubDir New value
     */
    public void setSubDir(final String newSubDir) {
        subdir = newSubDir;
    }

    /**
     * Create a new instance of JGitDescribeTask
     */
    public JGitDescribeTask() {
        dir = new File(".");
        shalength = 7;
        ref = "HEAD";
    }

    /**
     * Get a Revision Walker instance set up with the correct tree filter.
     *
     * @param repository Repository that should be walked.
     * @return RevWalker instance with Tree Filter if required.
     * @throws BuildException If the given subdir is invalid.
     */
    public RevWalk getWalk(final Repository repository) throws BuildException {
        RevWalk walk = null;
        walk = new RevWalk(repository);

        if (subdir != null) {
            final String parent = dir.getParent() + File.separator;
            for (String sd : subdir.split(";")) {
                sd = sd.replaceFirst("^" + Pattern.quote(parent), "");
                if (!new File(parent + sd).exists()) {
                    throw new BuildException("'"+sd+"' does not appear to be a subdir of this repo.");
                }
                // jgit is stupid on windows....
                final String filterDir = (File.separatorChar == '\\') ? sd.replace('\\', '/') : sd;
                walk.setTreeFilter(FollowFilter.create(filterDir));
            }
        }

        return walk;
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws BuildException {
        if (property == null) {
            throw new BuildException("\"property\" attribute must be set!");
        }

        try {
            GitDescribe gitDescribe= new GitDescribe.Builder(dir).setRef(ref)
                    .setShalength(shalength).setSubdir(subdir).build();    
            getProject().setProperty(property, gitDescribe.toString());
        } catch (Exception e) {
            throw new BuildException(e.getMessage());
        }
    }
}
