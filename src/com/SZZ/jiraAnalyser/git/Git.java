package com.SZZ.jiraAnalyser.git;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FilenameUtils;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import com.SZZ.jiraAnalyser.entities.*;
import com.SZZ.jiraAnalyser.entities.Transaction.FileInfo;




public class Git {

	public final String cloneCommand;
	public final File storagePath;
	public final String pullCommand;
	public final File workingDirectory;
	public final String logCommand;
	public final File logFile;
	public final File csvFile;
	private BlameResult blame;
	private String lastAnalysedCommit;
	private String lastAnalysedFile;

	private static final char DELIMITER = ';';

	public Git(Path storagePath, URL url) {
		this.cloneCommand = "git clone " + url.toString();
		this.storagePath = storagePath.toFile();
		this.pullCommand = "git pull";
		this.workingDirectory = gitWorkingDirectory(storagePath, url);
		this.logCommand ="git log " +
				"--pretty=format:\'" +
				"%H" + DELIMITER +
				"%aI" + DELIMITER +
				"%aN" + DELIMITER +
				"%s" +  DELIMITER +
				"\' " +
				"--name-status -M100%";
		this.logFile = this.gitLogFile(storagePath, url);
		this.csvFile = this.csvFile(storagePath, url);
		workingDirectory.delete();
	}

	private String gitDirectory(URL url) {
		final String link = url.toString();
		return link.substring(link.lastIndexOf('/') + 1, link.lastIndexOf('.'));
	}

	private File gitWorkingDirectory(Path storagePath, URL url) {
		return Paths.get(storagePath.toString(), this.gitDirectory(url)).toFile();
	}

	private File gitLogFile(Path storagePath, URL url) {
		return Paths.get(storagePath.toString(), this.gitDirectory(url) + ".txt")
				.toFile();
	}

	private File csvFile(Path storagePath, URL url) {
		return Paths.get(storagePath.toString(), this.gitDirectory(url) + ".csv")
				.toFile();
	}

	public void cloneRepository() throws Exception {
		if (!Files.exists(workingDirectory.toPath())) {
			execute(this.cloneCommand, this.storagePath);
		}
	}

	public void pullUpdates() throws Exception {
		execute(this.pullCommand, this.workingDirectory);
	}

	public void saveLog() throws Exception {
		executeToFile(this.logCommand, this.workingDirectory, this.logFile);
	}

	private void execute(String command, File directory)
			throws Exception {
		System.out.println("$ " + command);
		ProcessBuilder pb = new ProcessBuilder(command.split(" "));
		pb.directory(directory);
		pb.redirectErrorStream(true);
        pb.redirectOutput(Redirect.INHERIT);
        Process p = pb.start();
        p.waitFor();
	}

	private void executeToFile(
			String command, File workingDirectory, File destinationFile)
					throws Exception  {
		System.out.println("$ " + command + " > " + destinationFile);
		ProcessBuilder pb = new ProcessBuilder(command.split(" "));
		pb.directory(workingDirectory);
		pb.redirectOutput(destinationFile);
		Process p = pb.start();
        p.waitFor();
	}

	public List<Transaction> getCommits(String message) {
		List<Transaction> transactions = new ArrayList<Transaction>();
		
		 String line="";
		 String line1="";
		 String hashId = "";
		try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
			while ((line = br.readLine()) != null) {
		       if (!line.isEmpty() && line.startsWith("\'")){
		    	   line = line.replaceAll("\'", "");
		    	   String[] array = line.split(";");
				   String comment = array[3];

				   if (message != null && !comment.toLowerCase().contains(message.toLowerCase())) continue;

		    	   hashId = array[0];
				   String timestamp = array[1];
				   String author = array[2];
		       List<FileInfo> filesAffected = new ArrayList<FileInfo>();
		       line1 = br.readLine();
		       while (line1 != null && !line1.equals("")){
		    	   int BUFFER_SIZE = 100000;
		    	   br.mark(BUFFER_SIZE);
		    	   if (!line1.startsWith("\'")){
		    		   String[] subarray = line1.split("	");
		    		   String status = subarray[0];
		    		   String file = subarray[1];
		    		   FileInfo fileInfo = new FileInfo(status, file);
		    		   filesAffected.add(fileInfo);}
		    	   else{
		    		 br.reset();
		    		 break;
		    	   }
		    	   line1 = br.readLine();
		       }
		       Transaction transaction = new Transaction(
						hashId,
						timestamp,
						author,
						comment,
						filesAffected
				);
				transactions.add(transaction);
		    }
		}
		}
		catch(Exception e){
				e.printStackTrace();

		}

		return transactions;
	}

	public List<Transaction> getCommits() {
		return getCommits(null);
	}

	  /**
	   * Returns String with differences of fileName done by a shaCommit
	   * @param shaCommit
	   * @param fileName
	   * @return
	   */
	  public  String getDiff (String shaCommit, String fileName)  {
		String result = "";
		try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(workingDirectory)){
			ObjectId  oldId = git.getRepository().resolve(shaCommit+"^^{tree}");
			ObjectId headId = git.getRepository().resolve(shaCommit + "^{tree}");
			ObjectReader reader = git.getRepository().newObjectReader();
			CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
			oldTreeIter.reset(reader, oldId);
			CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
			newTreeIter.reset(reader, headId);
		    List<DiffEntry> diffs= git.diff()
		            .setNewTree(newTreeIter)
		            .setOldTree(oldTreeIter)
		            .call();
		    ByteArrayOutputStream out = new ByteArrayOutputStream();
		    DiffFormatter df = new DiffFormatter(out);
		    df.setDiffComparator(RawTextComparator.WS_IGNORE_TRAILING);
		    df.setRepository(git.getRepository());

		    for(DiffEntry diff : diffs)
		    {
		      if (diff.getNewPath().contains(fileName)){
		    	// Print the contents of the DiffEntries
		    	  //System.out.println(diff.getNewPath());
		    	  df.format(diff);
		          diff.getOldId();
		          String diffText = out.toString("UTF-8");
		          result = diffText;
		          break;
		      }
		    }
			out.close();
			df.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
	    return result;
		}

	  /**
	   * It gets removed lines from a commit starting from the diffString
	   * @param diffString
	   * @param filename
	   * @return
	   */
	  public  List<Integer> getLinesMinus(String diffString, String filename){

		  int actualInt = 1;
		  boolean actualSet = false;
		  List<Integer> listMinus = new LinkedList<Integer>();
		  try (Scanner scanner = new Scanner(diffString)){
		  scanner.nextLine();
		  scanner.nextLine();
		  scanner.nextLine();
		  scanner.nextLine();
		  while (scanner.hasNextLine()) {
		    String line = scanner.nextLine();
		     switch(line.charAt(0)){
		     case '-':
		    	 actualInt++;
				 if (line.length() <= 1) break;
				 if (lineHasOnlyCommentWithoutCode(FilenameUtils.getExtension(filename).toLowerCase(), line.substring(1))) break;
				 listMinus.add(actualInt);
		    	 break;
		     case '+':
		     	break;
		     case '@':
		    	 int stringMinus = line.indexOf('-');
		    	 int stringCommma = line.indexOf(',');
		    	 stringMinus++;
		    	 String sM = line.substring(stringMinus, stringCommma);
		    	 actualInt = Integer.parseInt(sM) - 1;
		    	 actualSet = true;
		    	 break;
		     default:
		    	 if (actualSet)
		    		 actualInt++;
		    	 break;
		     }
		  }
		  }
		  catch(Exception e){
			  return null;
		  }
		  return listMinus;

	  }


	  /**
	   * It gets blame of a file at a specific commit time
	   * index 0 of array ==> line 0
	   * index 1 of array ==> line 1
	   * index 2 of array ==> line 2
	   * @param commitSha
	   * @param file
	   * @param lineNumber
	   * @return
	   */
	  public String getBlameAt(String commitSha, String file, int lineNumber) {
		if (this.blame == null 
			|| !this.lastAnalysedCommit.equals(commitSha) 
			|| !this.lastAnalysedFile.equals(file)) {
			resetBlameResult(commitSha, file);
		}
		try {
			if (this.blame == null) return null;
			RevCommit resultCommit = this.blame.getSourceCommit(lineNumber - 1);
			return resultCommit.getName();
		} catch (Exception e) {
			return null;
		}
	  }

	  private void resetBlameResult(String commitSha, String file) {
		  lastAnalysedCommit = commitSha;
		  lastAnalysedFile = file;
		  try(Repository repository = org.eclipse.jgit.api.Git.open(workingDirectory).getRepository()){
			  BlameCommand blamer = new BlameCommand(repository);
			  ObjectId commitID = repository.resolve(commitSha);
			  blamer.setStartCommit(commitID);
			  blamer.setFilePath(file);
			  this.blame = blamer.call();
		  } catch (Exception e) {
			  this.blame = null;
		  }
	  }

	  /**
	   * It gets commit object starting from a specific sha
	   *
	   */
	  public RevCommit getCommit(String sha){
		  try(
			  Repository repository = org.eclipse.jgit.api.Git.open(workingDirectory).getRepository();
			  RevWalk walk = new RevWalk(repository)) {
			  ObjectId commitId = ObjectId.fromString(sha);
			  return walk.parseCommit(commitId);
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
	  }

	  /**
	   * Get Commit before the parameter commit
	   * @param sha
	   * @return
	   */
	  public String getPreviousCommit (String sha){
		  try {
		    RevCommit revCommit = getCommit(sha);
		    RevCommit parent = revCommit.getParent(0);
			return parent.getName();
		  } catch (Exception e) {
			  e.printStackTrace();
			return null;
		}
	  }

	private static Boolean lineHasOnlyCommentWithoutCode(String fileExtension, String line) {
		List<String> filesWithCStyleComments = List.of("c","java","cs","cpp","h","js","ts","swift","m","mm","r");
		List<String> filesWithShellStyleComments = List.of("sh","bash","zsh","pl");
		List<String> markupFiles = List.of("html","htm","xml");
		List<String> cssFiles = List.of("css","sass","scss","less");

		String regex = filesWithCStyleComments.contains(fileExtension)
				? "^\\s*(//|/\\*(?!.*?\\*/)|/\\*.*?\\*/\\s*$|\\*)"
				: filesWithShellStyleComments.contains(fileExtension)
				? "^\\s*#"
				: markupFiles.contains(fileExtension)
				? "^\\s*(<!--(?!.*?-->)|<!--.*?-->\\s*$)"
				: fileExtension.equals("py")
				? "^\\s*(#|\"\"\"(?!.*?\"\"\")|\"\"\".*?\"\"\"\\s*$)"
				: fileExtension.equals("sql")
				? "^\\s*(--|/\\*(?!.*?\\*/)|/\\*.*?\\*/\\s*$|\\*)"
				: fileExtension.equals("rb")
				? "^\\s*(#|=begin(?!.*?=end)|=begin.*?=end\\s*$)"
				: fileExtension.equals("php")
				? "^\\s*(#|//|/\\*(?!.*?\\*/)|/\\*.*?\\*/\\s*$|\\*)"
				: cssFiles.contains(fileExtension)
				? "^\\s*(/\\*(?!.*?\\*/)|/\\*.*?\\*/\\s*$|\\*)"
				: null;
		if (regex == null) return false;

		Pattern pattern = Pattern.compile(regex);
		Matcher matcher = pattern.matcher(line);
		return matcher.find();
	}
}
