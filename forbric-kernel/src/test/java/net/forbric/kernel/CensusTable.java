/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

/**
 * The one thing the eight census-table producers share with the build: a JUnit tag.
 *
 * <p>Each producer is an ordinary test in the ordinary suite, and on the default line it runs there and nowhere else.
 * On another line the suite cannot run at all ({@code test} depends on {@code compileRuntimeJava}, which does not
 * compile off the default line), so the build gained a task of its own — {@code lineCensus} — that runs
 * <em>only</em> the tagged methods, on that line's classpath. Tagging rather than listing class names in the build file
 * keeps the selection where the producer is: a census table that gains a producer gains the tag with it, and a task
 * whose filter matches nothing fails rather than reporting a silent zero.
 *
 * <p>Only the {@code theShippedTableIsExactlyWhatTheArtifactsSay} method of each producer is tagged. The other methods
 * in those classes assert <em>26.2</em> facts about the shipped rows — {@code lambda$startSleepInBed$0} is one of them
 * — and a run for another line must not ask them whether this line agrees with 26.2's answer.
 */
public final class CensusTable {
	/** The tag the line-census task selects, and the only tag any census producer carries. */
	public static final String TAG = "census-table";

	private CensusTable() {
	}
}
