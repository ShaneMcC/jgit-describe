package org.mdonoughe;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import java.util.regex.Pattern;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.Path;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.RepositoryBuilder;
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
     * Take a file, and parse its contents into a String.
     *
     * @param file File to read.
     * @return String content of file.
     */
    private String fileAsString(final File file) throws IOException {
        final StringBuffer fileData = new StringBuffer(1000);
        final BufferedReader reader = new BufferedReader(new FileReader(file));
        char[] buf = new char[1024];
        int numRead = 0;
        while ((numRead = reader.read(buf)) != -1 ) {
            fileData.append(buf, 0, numRead);
        }
        reader.close();
        return fileData.toString();
    }

    /**
     * Take a file, and try and resolve it as a .git directory.
     *
     * @param file File to parse
     * @return File possibly pointing to a .git directory.
     *         If the given file is already a directory, it will be returned,
     *         If it is a file, it will be attempted to be parsed as .git-file
     *         to find a directory. This is done recursively until a valid
     *         directory is found, or a file can't be parsed. (In which case
     *         the last successful directory will be returned, or the file given).
     *         Ultimately, a File object will be returned, that will either be what
     *         was passed into us, or a directory that may or may not be a real .git
     */
    public File getGitDir(final File file) {
        if (!file.isDirectory()) {
            try {
                final String content = fileAsString(file);
                final String[] bits = content.split(":", 2);
                if (bits.length > 1) {
                    final File res;
                    // Is this a relative path or an absolute path?
                    if (bits[1].trim().charAt(0) == '.') {
                        res = new File(file.getParent() + File.separatorChar + bits[1].trim());
                    } else {
                        res = new File(bits[1].trim());
                    }
                    return getGitDir(res);
                }
            } catch (final IOException ioe) {
               System.out.println("IOE: " + ioe.getMessage());
            }
        }
        return file;
    }

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
        dir = new File(".git");
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

        final File gitDir = getGitDir(dir);

        if (!gitDir.exists() || !gitDir.isDirectory() || !new File(gitDir, "config").exists()) {
            throw new BuildException("directory " + dir + " ("+gitDir.toString()+") does not appear to be a valid .git directory.");
        }

        Repository repository = null;
        try {
            RepositoryBuilder builder = new RepositoryBuilder();
            repository = builder.setGitDir(gitDir).build();
        } catch(IOException e) {
            throw new BuildException("Could not open repository", e);
        }

        RevWalk walk = null;
        RevCommit start = null;
        try {
            walk = getWalk(repository);
            start = walk.parseCommit(repository.resolve(ref));
            walk.markStart(start);
            if (subdir != null) {
                final RevCommit next = walk.next();
                if (next != null) {
                    walk = getWalk(repository);
                    start = walk.parseCommit(next);
                }
            }
        } catch (IOException e) {
            throw new BuildException("Could not find target", e);
        }

        final Map<ObjectId, RevTag> tags = new HashMap<ObjectId, RevTag>();

        for (Map.Entry<String, Ref> tag : repository.getTags().entrySet()) {
            try {
                final RevTag r = walk.parseTag(tag.getValue().getObjectId());
                final ObjectId taggedCommit = r.getObject().getId();
                // Has this commit already been seen with a different tag?
                if (tags.containsKey(taggedCommit)) {
                    final RevTag old = tags.get(taggedCommit);
                    final Long myTime = (r.getTaggerIdent() == null) ? 0 : r.getTaggerIdent().getWhen().getTime();
                    final Long oldTime = (old.getTaggerIdent() == null) ? 0 : old.getTaggerIdent().getWhen().getTime();
                    // Skip this commit if the old one is newer.
                    if (oldTime > myTime) {
                        continue;
                    }
                }
                tags.put(taggedCommit, r);
            } catch (IOException e) {
                // Theres really no need to panic yet.
            }
        }

        // No tags found. Panic.
        if (tags.isEmpty()) {
            throw new BuildException("No tags found.");
        }

        final List<RevCommit> taggedParents = taggedParentCommits(walk, start, tags);
        RevCommit best = null;
        int bestDistance = 0;
        for (RevCommit commit : taggedParents) {
            int distance = distanceBetween(start, commit);
            if (best == null || (distance < bestDistance)) {
                best = commit;
                bestDistance = distance;
            }
        }

        final StringBuilder sb = new StringBuilder();
        if (best != null) {
            sb.append(tags.get(best.getId()).getTagName());
            if (bestDistance > 0) {
                sb.append("-");
                sb.append(bestDistance);
                sb.append("-g");
            }
        }
        if (bestDistance > 0) {
            sb.append(start.getId().abbreviate(shalength).name());
        }

        getProject().setProperty(property, sb.toString());
    }

    /**
     * This does something. I think it gets every possible parent tag this
     * commit has, then later we look for which is closest and use that as
     * the tag to describe. Or something like that.
     *
     * @param walk
     * @param child
     * @param tagmap
     * @return
     * @throws BuildException
     */
    private List<RevCommit> taggedParentCommits(final RevWalk walk, final RevCommit child, final Map<ObjectId, RevTag> tagmap) throws BuildException {
        final Queue<RevCommit> q = new LinkedList<RevCommit>();
        q.add(child);
        final List<RevCommit> taggedcommits = new LinkedList<RevCommit>();
        final Set<ObjectId> seen = new HashSet<ObjectId>();

        while (q.size() > 0) {
            final RevCommit commit = q.remove();
            if (tagmap.containsKey(commit.getId())) {
                taggedcommits.add(commit);
                // don't consider commits that are farther away than this tag
                continue;
            }
            for (RevCommit p : commit.getParents()) {
                if (!seen.contains(p.getId())) {
                    seen.add(p.getId());
                    try {
                        q.add(walk.parseCommit(p.getId()));
                    } catch (IOException e) {
                        throw new BuildException("Parent not found", e);
                    }
                }
            }
        }
        return taggedcommits;
    }

    /**
     * Calculate the distance between 2 given commits, parent and child.
     *
     * @param child Commit to calculate distance to (The latest commit)
     * @param parent Commit to calculate distance from (The last tag)
     * @return Numeric value between the 2 commits.
     */
    private int distanceBetween(final RevCommit child, final RevCommit parent) {
        final Set<ObjectId> seen = new HashSet<ObjectId>();
        final Queue<RevCommit> q1 = new LinkedList<RevCommit>();
        final Queue<RevCommit> q2 = new LinkedList<RevCommit>();

        q1.add(child);
        int distance = 1;
        while ((q1.size() > 0) || (q2.size() > 0)) {
            if (q1.size() == 0) {
                distance++;
                q1.addAll(q2);
                q2.clear();
            }
            final RevCommit commit = q1.remove();
            if (commit.getParents() == null) {
                return 0;
            } else {
                for (RevCommit p : commit.getParents()) {
                    if (p.getId().equals(parent.getId())) {
                        return distance;
                    }
                    if (!seen.contains(p.getId())) {
                        q2.add(p);
                    }
                }
            }
            seen.add(commit.getId());
        }
        return distance;
    }
}
