// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.runtime;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.test.TestAttempt;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.concurrent.ThreadSafety;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import java.util.ArrayList;
import java.util.List;

/** This class aggregates and reports target-wide test statuses in real-time. */
@ThreadSafety.ThreadSafe
final class TestResultAggregator {
  /**
   * Settings for the aggregator; there are usually many aggregator instances with the same set of
   * settings, so we move them to a separate object.
   */
  static final class AggregationPolicy {
    private final EventBus eventBus;
    private final boolean testCheckUpToDate;
    private final boolean testVerboseTimeoutWarnings;

    AggregationPolicy(
        EventBus eventBus, boolean testCheckUpToDate, boolean testVerboseTimeoutWarnings) {
      this.eventBus = eventBus;
      this.testCheckUpToDate = testCheckUpToDate;
      this.testVerboseTimeoutWarnings = testVerboseTimeoutWarnings;
    }
  }

  private final AggregationPolicy policy;
  private final TestSummary.Builder summary;
  private int remainingRuns;
  private boolean summaryPosted = false;

  TestResultAggregator(
      ConfiguredTarget target,
      BuildConfiguration configuration,
      AggregationPolicy policy,
      boolean skippedThisTest) {
    this.policy = policy;
    this.summary =
        TestSummary.newBuilder(target)
            .setConfiguration(configuration)
            .setStatus(BlazeTestStatus.NO_STATUS)
            .setSkipped(skippedThisTest);
    this.remainingRuns = TestProvider.getTestStatusArtifacts(target).size();
  }

  /**
   * Records a new test run result and incrementally updates the target status. This event is sent
   * upon completion of executed test runs.
   */
  synchronized void testEvent(TestResult result) {
    // If a test result was cached, then post the cached attempts to the event bus.
    if (result.isCached()) {
      for (TestAttempt attempt : result.getCachedTestAttempts()) {
        policy.eventBus.post(attempt);
      }
    }

    remainingRuns--;
    if (summaryPosted) {
      // This can happen if a buildCompleteEvent() was processed before this event reached us.
      // This situation is likely to happen if --notest_keep_going is set with multiple targets.
      return;
    }

    incrementalAnalyze(result);

    // If all runs are processed, the target is finished and ready to report.
    if (remainingRuns == 0) {
      postSummary();
    }
  }

  private TestSummary postSummary() {
    TestSummary result = summary.build();
    policy.eventBus.post(result);
    summaryPosted = true;
    return result;
  }

  synchronized void targetFailure(boolean blazeHalted, boolean skipTargetsOnFailure) {
    if (summaryPosted) {
      // Blaze does not guarantee that BuildResult.getSuccessfulTargets() and posted TestResult
      // events are in sync. Thus, it is possible that a test event was posted, but the target is
      // not present in the set of successful targets.
      return;
    }

    markUnbuilt(blazeHalted, skipTargetsOnFailure);

    // These are never going to run; removing them marks the target complete.
    postSummary();
  }

  synchronized void targetSkipped() {
    if (summaryPosted) {
      // Blaze does not guarantee that BuildResult.getSuccessfulTargets() and posted TestResult
      // events are in sync. Thus, it is possible that a test event was posted, but the target is
      // not present in the set of successful targets.
      return;
    }

    summary.setStatus(BlazeTestStatus.NO_STATUS);

    // These are never going to run; removing them marks the target complete.
    postSummary();
  }

  static BlazeTestStatus aggregateStatus(BlazeTestStatus status, BlazeTestStatus other) {
    return status.getNumber() > other.getNumber() ? status : other;
  }

  /**
   * Helper for differential analysis which aggregates the TestSummary for an individual target,
   * reporting runs on the EventBus if necessary.
   */
  synchronized TestSummary aggregateAndReportSummary(boolean skipTargetsOnFailure) {
    // If already reported by the listener, no work remains for this target.
    if (summaryPosted) {
      return summary.build(); // Reuses the same summary if nothing has changed.
    }

    // Build may have been interrupted.
    if (remainingRuns > 0) {
      markIncomplete(skipTargetsOnFailure);
    }

    return postSummary();
  }

  /**
   * Incrementally updates a TestSummary given an existing summary and a new TestResult. Only call
   * on built targets.
   *
   * @param result New test result to aggregate into the summary.
   */
  private void incrementalAnalyze(TestResult result) {
    // Cache retrieval should have been performed already.
    TestSummary existingSummary = Preconditions.checkNotNull(summary.peek());

    BlazeTestStatus status = existingSummary.getStatus();
    int numCached = existingSummary.numCached();
    int numLocalActionCached = existingSummary.numLocalActionCached();

    // If a test was neither cached locally nor remotely we say action was taken.
    if (!(result.isCached() || result.getData().getRemotelyCached())) {
      summary.setActionRan(true);
    } else {
      numCached++;
    }

    if (result.isCached()) {
      numLocalActionCached++;
    }

    Path coverageData = result.getCoverageData();
    if (coverageData != null) {
      summary.addCoverageFiles(ImmutableList.of(coverageData));
    }

    TransitiveInfoCollection target = existingSummary.getTarget();
    Preconditions.checkNotNull(target, "The existing TestSummary must be associated with a target");
    TestParams testParams = target.getProvider(TestProvider.class).getTestParams();

    if (!testParams.runsDetectsFlakes()) {
      status = aggregateStatus(status, result.getData().getStatus());
    } else {
      int shardNumber = result.getShardNum();
      int runsPerTestForLabel = testParams.getRuns();
      List<BlazeTestStatus> singleShardStatuses =
          summary.addShardStatus(shardNumber, result.getData().getStatus());
      if (singleShardStatuses.size() == runsPerTestForLabel) {
        // Aggregation is based on the order of status enums where larger values take precedence
        // over smaller ones (NO_STATUS = 0, PASSED = 1, etc.). However, there are some special
        // cases:
        // 1. Tests that have some passing, some not passing shard are marked as flaky
        // 2. The INCOMPLETE status is ignored - it is used for tests runs that are cancelled by
        //    Bazel if --cancel_concurrent_tests is set, otherwise INCOMPLETE is not used
        // 3. Individual test shards can be FLAKY if the test is marked flaky and
        //    --flaky_test_attempts is not zero for this test
        BlazeTestStatus shardStatus = BlazeTestStatus.NO_STATUS;
        int passes = 0;
        int cancelled = 0;
        for (BlazeTestStatus runStatusForShard : singleShardStatuses) {
          if (runStatusForShard == BlazeTestStatus.INCOMPLETE) {
            // If runs_per_test_detects_flakes is enabled, then INCOMPLETE status indicates
            // cancelled test runs. We count them separately so that they don't result in a
            // flaky status below.
            cancelled++;
          } else {
            shardStatus = aggregateStatus(shardStatus, runStatusForShard);
            if (TestResult.isBlazeTestStatusPassed(runStatusForShard)) {
              passes++;
            }
          }
        }
        // Under the RunsPerTestDetectsFlakes option, return flaky if 0 < p < (n-cancelled) shards
        // pass. Otherwise, we aggregate the shardStatus.
        if (passes == 0 || (passes + cancelled) == runsPerTestForLabel) {
          status = aggregateStatus(status, shardStatus);
        } else {
          status = aggregateStatus(status, BlazeTestStatus.FLAKY);
        }
      }
    }

    if (result.getData().hasPassedLog()) {
      summary.addPassedLog(result.getTestLogPath().getRelative(result.getData().getPassedLog()));
    }
    for (String path : result.getData().getFailedLogsList()) {
      summary.addFailedLog(result.getTestLogPath().getRelative(path));
    }

    summary
        .addTestTimes(result.getData().getTestTimesList())
        .mergeTiming(
            result.getData().getStartTimeMillisEpoch(), result.getData().getRunDurationMillis())
        .addWarnings(result.getData().getWarningList())
        .collectTestCases(result.getData().hasTestCase() ? result.getData().getTestCase() : null)
        .setRanRemotely(result.getData().getIsRemoteStrategy());

    List<String> warnings = new ArrayList<>();
    if (status == BlazeTestStatus.PASSED
        && shouldEmitTestSizeWarningInSummary(
            policy.testVerboseTimeoutWarnings,
            warnings,
            result.getData().getTestProcessTimesList(),
            target)) {
      summary.setWasUnreportedWrongSize(true);
    }

    summary
        .mergeSystemFailure(result.getSystemFailure())
        .setStatus(status)
        .setNumCached(numCached)
        .setNumLocalActionCached(numLocalActionCached)
        .addWarnings(warnings);
  }

  private void markIncomplete(boolean skipTargetsOnFailure) {
    // TODO(bazel-team): (2010) Make NotRunTestResult support both tests failed to built and
    // tests with no status and post it here.
    TestSummary peekSummary = summary.peek();
    BlazeTestStatus status = peekSummary.getStatus();
    if (skipTargetsOnFailure) {
      status = BlazeTestStatus.NO_STATUS;
    } else if (status != BlazeTestStatus.NO_STATUS) {
      status = aggregateStatus(status, BlazeTestStatus.INCOMPLETE);
    }

    summary.setStatus(status);
  }

  private void markUnbuilt(boolean blazeHalted, boolean skipTargetsOnFailure) {
    BlazeTestStatus runStatus =
        blazeHalted
            ? BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING
            : (policy.testCheckUpToDate || skipTargetsOnFailure
                ? BlazeTestStatus.NO_STATUS
                : BlazeTestStatus.FAILED_TO_BUILD);

    summary.setStatus(runStatus);
  }

  /**
   * Checks whether the specified test timeout could have been smaller or is too small and adds a
   * warning message if verbose is true.
   *
   * <p>Returns true if there was a test with the wrong timeout, but if was not reported.
   */
  private static boolean shouldEmitTestSizeWarningInSummary(
      boolean verbose,
      List<String> warnings,
      List<Long> testTimes,
      TransitiveInfoCollection target) {

    TestTimeout specifiedTimeout =
        target.getProvider(TestProvider.class).getTestParams().getTimeout();
    long maxTimeOfShard = 0;

    for (Long shardTime : testTimes) {
      if (shardTime != null) {
        maxTimeOfShard = Math.max(maxTimeOfShard, shardTime);
      }
    }

    int maxTimeInSeconds = (int) (maxTimeOfShard / 1000);

    if (!specifiedTimeout.isInRangeFuzzy(maxTimeInSeconds)) {
      TestTimeout expectedTimeout = TestTimeout.getSuggestedTestTimeout(maxTimeInSeconds);
      TestSize expectedSize = TestSize.getTestSize(expectedTimeout);
      if (verbose) {
        StringBuilder builder =
            new StringBuilder(
                String.format(
                    "%s: Test execution time (%.1fs excluding execution overhead) outside of "
                        + "range for %s tests. Consider setting timeout=\"%s\"",
                    AliasProvider.getDependencyLabel(target),
                    maxTimeOfShard / 1000.0,
                    specifiedTimeout.prettyPrint(),
                    expectedTimeout));
        if (expectedSize != null) {
          builder.append(" or size=\"").append(expectedSize).append("\"");
        }
        builder.append(".");
        warnings.add(builder.toString());
        return false;
      }
      return true;
    } else {
      return false;
    }
  }
}
