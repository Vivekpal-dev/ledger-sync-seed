\# Simplify Money – Product Feedback \& Data-Source Teardown



\## Task 0 – Product Experience \& Feedback



\### Setup

I installed and explored the Simplify Money app and completed the initial profile onboarding.



The onboarding uses Kuber.AI to guide the user through the setup process. The app also uses PAN-based information to pre-fill profile details for review and confirmation.



\### What I liked



1\. \*\*Guided onboarding\*\*

&#x20;  - The onboarding flow is simple and conversational.

&#x20;  - Kuber.AI provides clear guidance while collecting profile information.



2\. \*\*PAN-based autofill\*\*

&#x20;  - Profile information can be pre-filled from PAN data.

&#x20;  - The user is shown the information for review before continuing.



3\. \*\*Clear account-linking entry points\*\*

&#x20;  - The home screen clearly exposes account linking.

&#x20;  - The app distinguishes between sample data and real linked-account data.



4\. \*\*Referral flow\*\*

&#x20;  - The Invite Friends option is easy to find from the Profile section.

&#x20;  - A referral code and sharing option are provided directly.



\### What could be improved



1\. \*\*Primary action hierarchy\*\*

&#x20;  - The home screen contains several different financial features, including Premium, Digital Gold, ITR, Quiz, SIP and account linking.

&#x20;  - For a new user whose primary goal is tracking finances, the account-linking action could receive stronger visual priority.



2\. \*\*Preview mode\*\*

&#x20;  - The app clearly indicates that sample data is being displayed.

&#x20;  - However, a new user may still benefit from a more prominent explanation of what functionality is unavailable until an account is linked.



3\. \*\*Bank coverage\*\*

&#x20;  - During my account-linking attempt, my bank, Bank of Baroda, was not present in the displayed bank-selection options.

&#x20;  - This prevented me from testing the complete real-account experience.



\### Referral test



I generated the referral invitation and shared it with three contacts.



I am not claiming that the three contacts completed registration because I did not independently verify that.



\---



\# Task 1 – Data Source / Track Teardown



\## Test objective



I attempted to connect my real bank account and evaluate the flow from account linking through transaction tracking.



\## Flow tested



1\. Opened the Simplify Money home screen.

2\. Observed that the app was initially displaying \*\*sample data / Preview Mode\*\*.

3\. Selected the account-linking option.

4\. Reached the \*\*Bank Selection\*\* screen.

5\. Checked the available banks.

6\. My bank, \*\*Bank of Baroda\*\*, was not present in the displayed options.

7\. I therefore stopped the flow instead of selecting another bank or using another person's credentials.



\## Permission / data access



The app provides separate entry points for account linking and SMS-based tracking. The home screen displayed an option to allow SMS access for transaction tracking.



Because my bank was unavailable in the account-linking flow, I could not complete a real bank-account sync and therefore could not honestly evaluate the complete transaction-sync pipeline.



\## What I could verify



\- The app distinguishes \*\*sample data\*\* from real linked-account data.

\- The home screen communicates that linking an account is required to see real financial numbers.

\- Account linking is accessible from the main dashboard.

\- The bank-selection screen presents a fixed list of supported banks.

\- Bank of Baroda was not present in the options shown to me.



\## What I could not verify



Because Bank of Baroda was unavailable:



\- I could not complete real bank authentication.

\- I could not observe the complete bank-sync process.

\- I could not evaluate the accuracy of the resulting transaction list.

\- I could not identify missed or incorrectly classified transactions from my own bank data.



I therefore do not claim that the transaction-sync accuracy is good or bad based on this test.



\## Trust assessment



The distinction between sample data and linked real data is useful because it prevents me from confusing demonstration data with my own financial information.



However, unsupported-bank coverage is a significant limitation for a user whose bank cannot be connected. The product should clearly communicate supported-bank coverage and provide a clear alternative when a user's bank is unavailable.



\## Three changes I would suggest



\### 1. Improve unsupported-bank handling



If a user's bank is unavailable, clearly explain this immediately and provide available alternatives, such as supported data sources or SMS-based tracking where applicable.



\*\*Why:\*\* The user currently reaches the bank-selection stage before discovering that their bank cannot be connected.



\### 2. Make account linking more prominent



Reduce competition from secondary features on the first-use dashboard and make the primary setup action more obvious.



\*\*Why:\*\* Linking an account is necessary to move from sample data to the user's real financial information.



\### 3. Explain data-source coverage



Add a short explanation near bank selection describing how the supported-bank list works and what users can do if their bank is missing.



\*\*Why:\*\* This reduces uncertainty and prevents users from assuming that their account can be connected when it cannot.



\## Evidence



Screenshots captured during testing:



\- Simplify Money profile/home screen

\- Preview Mode / sample-data screen

\- Invite Friends / referral screen

\- Bank Selection screen showing available banks



Personal information such as date of birth and phone number should be cropped or redacted before submitting screenshots.

