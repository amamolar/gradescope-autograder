package AutoGrader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;

/**
 * This class is used to output a Gradescope JSON file from JUnit tests.
 * Requires implementation in test files.
 *
 * https://github.com/cm090/gradescope-autograder
 * 
 * @author Canon Maranda
 * @version 4.2
 */
public class GradescopeAutoGrader {
    private HashMap<Integer, TestData> data;
    private HashMap<String, Integer> idList;
    private PrintStream output;
    private int nextId;
    private double assignmentTotalScore;
    private OutputMessage resultMessage;

    public GradescopeAutoGrader(double assignmentTotalScore) {
        this.data = new HashMap<Integer, TestData>();
        this.idList = new HashMap<String, Integer>();
        this.nextId = 1;
        this.assignmentTotalScore = assignmentTotalScore;
        this.resultMessage = OutputMessage.DEFAULT;
        try {
            this.output = new PrintStream(new FileOutputStream("results.json"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Adds a test file to the map of tests.
     * 
     * @param name       The name of the test
     * @param maxScore   The maximum score a student can get on the test
     * @param visibility "hidden", "after_due_date", "after_published", or "visible"
     */
    public void addTest(String name, String visibility) {
        idList.put(name, nextId);
        this.data.put(idList.get(name), new TestData(name, visibility));
        nextId++;
    }

    /**
     * Adds a result to an already existing test. takes in a name and number of
     * points.
     * 
     * @param name  The name of the test
     * @param grade The grade the student received on the test
     */
    public void addResult(String name, double numTests, double numFailed) {
        TestData current = this.data.get(idList.get(name));
        current.maxScore = numTests;
        current.setScore(numTests - numFailed);
        if (numTests == 0) {
            resultMessage = OutputMessage.TEST_RUNNER_FAILED;
        }
    }

    /**
     * This function takes in a name and output, and adds the output to that of the
     * test with the given name
     * 
     * @param name   The name of the test
     * @param output The output of the test
     */
    public void addFailure(String name, String output) {
        TestData current = data.get(idList.get(name));
        StringBuilder sb = new StringBuilder(current.output);
        sb.append(output.replaceAll("\\(.*\\):", ""))
                .append("\\n");
        current.output = sb.toString();
        current.visible = "visible";
    }

    /**
     * Converts map of scores to JSON. Exports to file for Gradescope to analyze.
     * 
     * @param percentage The percentage of the assignment that the student has
     *                   completed.
     */
    public void toJSON(double percentage) {
        percentage /= 100.0;
        StringJoiner tests = new StringJoiner(",");
        for (int key : this.data.keySet()) {
            TestData current = this.data.get(key);
            tests.add(String.format(
                    "{\"score\": %f, \"max_score\": %f, \"name\": \"%s\", \"number\": \"%d\", \"output\": \"%s\", %s \"visibility\": \"%s\"}",
                    current.grade, current.maxScore, current.name, key,
                    current.output.replace("\n", " ").replace("\t", " "),
                    (current.output.length() > 0) ? "\"status\": \"failed\"," : "", current.visible));
        }
        String json = String.format(
                "{ \"score\": %.2f, \"output\": \"%s\", \"visibility\": \"visible\", \"tests\":[%s]}",
                percentage * this.assignmentTotalScore, resultMessage.getMessage(), tests);
        output.append(json);
        output.close();
    }

    /**
     * Stores information about each test
     */
    class TestData {
        public double maxScore, grade;
        public String name, output, visible;

        public TestData(String name, String visibility) {
            this.name = name;
            this.maxScore = 0;
            this.grade = 0;
            this.visible = visibility;
            this.output = "";
        }

        public void setScore(double grade) {
            this.grade = grade;
        }
    }

    enum OutputMessage {
        DEFAULT("Your submission has been successfully graded. Failed test cases are shown below."),
        TEST_RUNNER_FAILED(
                "There was a problem with your code that caused some tests to unexpectedly fail. Please see the output below and resubmit.");

        private String message;

        OutputMessage(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Takes in a list of packages, finds all the classes in those packages, and
     * runs all the tests in those classes
     */
    public static void main(String[] args) throws InitializationError {
        try {
            if (args.length == 0)
                throw new IndexOutOfBoundsException();
            BufferedReader reader = new BufferedReader(new FileReader("../submission_metadata.json"));
            String submissionData = reader.readLine();
            Pattern pattern = Pattern.compile(
                    "\"type\":\\s*\"ProgrammingQuestion\",\\s*\"title\":\\s*\"Autograder\".*?\"weight\":\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(submissionData);
            double score = matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
            GradescopeAutoGrader g = new GradescopeAutoGrader(score);
            HashSet<Class<?>> allClasses = new HashSet<Class<?>>();
            for (int i = 0; i < args.length; i++)
                allClasses.addAll(ClassFinder.find(args[i]));
            HashSet<TestRunner> runners = new HashSet<TestRunner>();
            for (Class<?> c : allClasses) {
                if (!c.toString().contains("RunAllTests"))
                    // If you want to change the visibility of tests, add a String argument to the
                    // below TestRunner constructor. Supported inputs: hidden, after_due_date,
                    // after_published, visible
                    runners.add(new TestRunner(c, g));
            }
            for (TestRunner t : runners) {
                t.run(new RunNotifier());
            }
            reader.close();
        } catch (IndexOutOfBoundsException e) {
            System.err.println(
                    "Incorrect command line arguments\nUsage: java -cp bin/:lib/* AutoGrader.GradescopeAutoGrader [maxScore] [testPackages]");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error reading file submission_metadata.json");
            System.exit(1);
        }
    }

    // Below code found at https://stackoverflow.com/a/15519745
    private static class ClassFinder {
        private static final char PKG_SEPARATOR = '.';
        private static final char DIR_SEPARATOR = '/';
        private static final String CLASS_FILE_SUFFIX = ".class";
        private static final String BAD_PACKAGE_ERROR = "Unable to get resources from path '%s'. Are you sure the package '%s' exists?";

        public static List<Class<?>> find(String scannedPackage) {
            String scannedPath = scannedPackage.replace(PKG_SEPARATOR, DIR_SEPARATOR);
            URL scannedUrl = Thread.currentThread().getContextClassLoader().getResource(scannedPath);
            if (scannedUrl == null) {
                throw new IllegalArgumentException(String.format(BAD_PACKAGE_ERROR, scannedPath, scannedPackage));
            }
            File scannedDir = new File(scannedUrl.getFile());
            List<Class<?>> classes = new ArrayList<Class<?>>();
            for (File file : scannedDir.listFiles()) {
                classes.addAll(find(file, scannedPackage));
            }
            return classes;
        }

        private static List<Class<?>> find(File file, String scannedPackage) {
            List<Class<?>> classes = new ArrayList<Class<?>>();
            String resource = scannedPackage + PKG_SEPARATOR + file.getName();
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    classes.addAll(find(child, resource));
                }
            } else if (resource.endsWith(CLASS_FILE_SUFFIX)) {
                int endIndex = resource.length() - CLASS_FILE_SUFFIX.length();
                String className = resource.substring(0, endIndex);
                try {
                    classes.add(Class.forName(className));
                } catch (ClassNotFoundException ignore) {
                }
            }
            return classes;
        }
    }
}