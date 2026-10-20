# Stack Overflow System - Test Cases

## Test Case 1: User Creation
**Scenario:** Create a new user account
- **Given:** System is operational
- **When:** User registers with username, email
- **Then:** User account created with initial reputation = 1

## Test Case 2: Post a Question
**Scenario:** User posts a question with tags
- **Given:** User is logged in
- **When:** User posts question with title, body, and tags ["java", "spring"]
- **Then:** Question created with unique ID, timestamp, and associated tags

## Test Case 3: Answer a Question
**Scenario:** User answers a question
- **Given:** Question exists
- **When:** User posts an answer
- **Then:** Answer linked to question, author attribution correct

## Test Case 4: Comment on Question
**Scenario:** User adds comment to question
- **Given:** Question exists
- **When:** User posts comment
- **Then:** Comment associated with question, ordered by timestamp

## Test Case 5: Comment on Answer
**Scenario:** User adds comment to answer
- **Given:** Answer exists
- **When:** User posts comment
- **Then:** Comment associated with answer

## Test Case 6: Upvote Question
**Scenario:** User upvotes a question
- **Given:** Question exists, user hasn't voted
- **When:** User upvotes question
- **Then:** Question vote count increases by 1, author reputation +5

## Test Case 7: Downvote Answer
**Scenario:** User downvotes an answer
- **Given:** Answer exists, user hasn't voted
- **When:** User downvotes answer
- **Then:** Answer vote count decreases by 1, author reputation -2

## Test Case 8: Change Vote
**Scenario:** User changes vote from upvote to downvote
- **Given:** User has upvoted a question
- **When:** User downvotes same question
- **Then:** Previous vote removed, new vote applied, vote count adjusts by -2

## Test Case 9: Prevent Double Voting
**Scenario:** User tries to vote twice
- **Given:** User has already upvoted
- **When:** User tries to upvote again
- **Then:** System rejects duplicate vote

## Test Case 10: Search Questions by Keywords
**Scenario:** Search for questions containing specific keywords
- **Given:** Multiple questions exist
- **When:** Search for "java spring boot"
- **Then:** Returns questions containing any of these keywords in title/body

## Test Case 11: Search by Tags
**Scenario:** Find all questions with specific tag
- **Given:** Questions tagged with "java", "python"
- **When:** Search by tag "java"
- **Then:** Returns only questions tagged with "java"

## Test Case 12: Search by User
**Scenario:** Find all questions by specific user
- **Given:** User has posted multiple questions
- **When:** Search by user ID
- **Then:** Returns all questions authored by that user

## Test Case 13: Concurrent Voting
**Scenario:** Multiple users vote on same question simultaneously
- **Given:** Question with 0 votes
- **When:** 10 users upvote concurrently
- **Then:** Final vote count is exactly 10 (no lost updates)

## Test Case 14: Accept Answer
**Scenario:** Question author accepts an answer
- **Given:** Question with multiple answers
- **When:** Author marks one answer as accepted
- **Then:** Answer marked as accepted, author gets +15 reputation

## Test Case 15: View Questions Sorted by Votes
**Scenario:** Get questions ordered by vote count
- **Given:** Questions with varying vote counts
- **When:** Request sorted list
- **Then:** Questions returned in descending vote order

## Test Case 16: User Reputation System
**Scenario:** Track user reputation through various actions
- **Given:** New user (reputation = 1)
- **When:** User posts question (+5), receives upvote on answer (+10)
- **Then:** Reputation = 1 + 5 + 10 = 16

## Test Case 17: Get Question with Answers
**Scenario:** Retrieve question details with all answers
- **Given:** Question with 3 answers
- **When:** Request question by ID
- **Then:** Returns question with all answers sorted by votes

## Test Case 18: Tag Statistics
**Scenario:** Get popular tags
- **Given:** Multiple questions with various tags
- **When:** Request tag statistics
- **Then:** Returns tags with usage count, sorted by popularity

## Test Case 19: Edit Question
**Scenario:** Author edits their question
- **Given:** Existing question
- **When:** Author updates title/body
- **Then:** Question updated, edit history tracked

## Test Case 20: Delete Question
**Scenario:** Author or admin deletes question
- **Given:** Question with answers and comments
- **When:** Delete request issued
- **Then:** Question marked as deleted, cascade to answers/comments
