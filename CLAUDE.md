# Purpose and outlook

This project contains scientific software: a MCP server that provides
Bio-Formats reader / metadata capability for AI agents.

Because this is scientific software, **every error that is caught and
ignored must be justified**.  It is very important that we not give users a
false confidence that information has been incorporated properly when it has
not; this can lead to incorrect conclusions.

For example, if one can use either of two TIFF readers, and one fails to
load, it is fine to fail over to the other one.  However, if we expect to
get summary data from five images and only four load successfully it is NOT
okay to make a "best effort" and return summary data incorporating only the
four images.  The fifth may have been essential--we don't know.  The error
must be propagated.

This point is so important that it bears repeating.  Every error that is
caught and ignored must be justified!  It is very important that we not give
users a false confidence that information has been incorporated properly
when it has not.

Although this is a tool for AI agents to use, they cannot properly reason
about what to do if the tool itself fails to return all potentially relevant
errors.

# Important documents.

It is important to be familiar with @README.md and @DESIGN.md since these
documents explain the project in the greatest detail.
