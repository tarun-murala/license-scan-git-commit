# server-side-hooks
Server Side Hooks

This is prototype to validate the license compliance for the source code (maintained through git versioning systems)

Post-Receive Hook
   - A symlink has to created from the .hooks (incase if .coreHooks are not configured)
   - Or use templates incase
 
 
 Logic:
   1. Read the Last Git commit
   2. For each file revisions in the part of commit
   3. Retrieve the branch name on which this commit was made
   4. Fetch the file from the branch and Save it under OUTPUTS/ folder
   5. If the file has ADDED
         i. If the file is packaging file - check and report for the licenses
         ii. If the file is compressed file - simply report
         iii. If the file neither of them - Scan for the licenses and copyrights
   6. If the file has MODIFIED
         i. If the file is packaging file - check and report for the licenses
         ii. If the file is compressed file - simply report
         iii. If the file neither of them - Scan for the licenses and copyrights
   7. Frame a response for each result, Aggregate the same and report (generate a JSON or so)
   8. Delete the OUTPUTS/ folder


Note: This prototype uses scancode to scan the code for licenses and copyrights
