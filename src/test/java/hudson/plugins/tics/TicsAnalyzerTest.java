package hudson.plugins.tics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import hudson.EnvVars;
import hudson.util.ArgumentListBuilder;

public class TicsAnalyzerTest {

    public final Metrics calcMetrics = getMetrics(true, false, false, true);

    public final Metrics recalcMetrics = getMetrics(false, true, true, false);

    public final String ticsPath = "";
    public final String ticsConfiguration = "http://192.168.1.204:42506/tiobeweb/TICS/api/cfg?name=default";
    public final String projectName = "cpp-game-vs";
    public final String branchName = "master";
    public final String branchDirectory = "D:\\Development\\dev_test\\projects\\cpp-game-vs" ;
    public final String environmentVariables = "";
    public final boolean createTmpdir = true;
    public final String tmpdir = "D:\\Development\\dev_test\\tmp\\33733-tmpdir" ;
    public final String extraArguments = "";
    public final boolean installTics = false;
    public final String credentialsId = "token-leila-machine";
    private final TicsAnalyzer ta = new TicsAnalyzer(ticsPath
            , ticsConfiguration
            , projectName
            , branchName
            , branchDirectory
            , environmentVariables
            , createTmpdir
            , tmpdir
            , extraArguments
            , calcMetrics
            , recalcMetrics
            , installTics
            , credentialsId);


    private Metrics getMetrics(final boolean codingStandards, final boolean compilerWarnings, final boolean finalize, final boolean loc) {
        return new Metrics(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                codingStandards,
                compilerWarnings,
                false,
                false,
                false,
                false,
                finalize,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                loc,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }


    @Test
    public void testGetTicsAnalysisCmdEscapedWin() {
        final EnvVars buildEnv = new EnvVars();
        final boolean isLauncherUnix = false;
        final ArgumentListBuilder ticsAnalysisCmd = ta.getTicsQServerArgs(buildEnv, isLauncherUnix);
        final String ticsAnalysisCmdEscapedWin = ta.getTicsAnalysisCmdEscapedWin(ticsAnalysisCmd);
        final String ticsAnalysisCmdExpected = "cmd.exe /C \"TICSQServer.exe -project cpp-game-vs -branchname master -branchdir D:\\Development\\dev_test\\projects\\cpp-game-vs -tmpdir D:\\Development\\dev_test\\tmp\\33733-tmpdir -calc CODINGSTANDARD,LOC -recalc COMPILERWARNING,FINALIZE && exit %%ERRORLEVEL%%\"";
        assertEquals(ticsAnalysisCmdExpected, ticsAnalysisCmdEscapedWin);
    }

    @Test
    public void testRemoveDoubleQuoteFromCommand() {
        String ticsAnalysisCmd;
        String ticsAnalysisCmdExpected;

        ticsAnalysisCmd = "cmd.exe /C \"TICSQServer.exe -project game-gcc -branchname master -branchdir D:\\Development\\dev_test\\workspace\\tics-test-33733-UI -tmpdir D:\\Development\\dev_test\\tmp\\33733-tmpdir -calc \"CODINGSTANDARD,LOC\" && exit %%ERRORLEVEL%%\"";
        ticsAnalysisCmdExpected = "cmd.exe /C \"TICSQServer.exe -project game-gcc -branchname master -branchdir D:\\Development\\dev_test\\workspace\\tics-test-33733-UI -tmpdir D:\\Development\\dev_test\\tmp\\33733-tmpdir -calc CODINGSTANDARD,LOC && exit %%ERRORLEVEL%%\"";
        assertEquals(ticsAnalysisCmdExpected, ta.removeDoubleQuoteFromCommand("calc", ticsAnalysisCmd));

        ticsAnalysisCmd = "cmd.exe /C \"TICSQServer.exe -project cpp-game-vs -branchname master -branchdir D:\\Development\\dev_test\\projects\\cpp-game-vs -tmpdir D:\\Development\\dev_test\\tmp\\33733-tmpdir -calc \"CODINGSTANDARD,LOC\" -recalc \"COMPILERWARNING,FINALIZE\" && exit %%ERRORLEVEL%%\"";
        ticsAnalysisCmdExpected = "cmd.exe /C \"TICSQServer.exe -project cpp-game-vs -branchname master -branchdir D:\\Development\\dev_test\\projects\\cpp-game-vs -tmpdir D:\\Development\\dev_test\\tmp\\33733-tmpdir -calc CODINGSTANDARD,LOC -recalc COMPILERWARNING,FINALIZE && exit %%ERRORLEVEL%%\"";
        assertEquals(ticsAnalysisCmdExpected, ta.removeDoubleQuoteFromCommand("calc", ticsAnalysisCmd));
    }
}
