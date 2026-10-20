# System: Design 
## Parking lot
### Requirements:
- The parking lot should have multiple levels, each level with a certain number of parking spots. 
- It should support different types of vehicles, such as cars, motorcycles, and trucks. 
- Each parking spot should be able to accommodate a specific type of predefined vehicle. 
- The system should assign a parking spot to a vehicle upon entry and release it when the vehicle exits. 
- The system should track the availability of parking spots and provide real-time information to customers. 
- The system should handle multiple entry and exit points and support concurrent access.
- System must generate ticket for each vehicle and charge based on the time of parking.

## Stack Overflow

### Requirements:
- Users can post questions, answer questions, and comment on questions and answers. 
- Users can vote on questions and answers. 
- Questions should have tags associated with them. 
- Users can search for questions based on keywords, tags, or user profiles. 
- The system should handle concurrent access and ensure data consistency.


## Vending Machine

### Requirements:
- The vending machine should support multiple products with different prices and quantities.
- The machine should accept coins and notes of different denominations.
- The machine should dispense the selected product and return change if necessary.
- The machine should keep track of the available products and their quantities.
- The machine should handle multiple transactions concurrently and ensure data consistency.
- The machine should provide an interface for restocking products and collecting money.
- The machine should handle exceptional scenarios, such as insufficient funds or out-of-stock products.

## Designing a Logging Framework
### Requirements:
- The logging framework should support different log levels, such as DEBUG, INFO, WARNING, ERROR, and FATAL.
- It should allow logging messages with a timestamp, log level, and message content.
- The framework should support multiple output destinations, such as console, file, and database.
- It should provide a configuration mechanism to set the log level and output destination.
- The logging framework should be thread-safe to handle concurrent logging from multiple threads.
- It should be extensible to accommodate new log levels and output destinations in the future.

## Traffic Signal Control System
### Requirements
- The traffic signal system should control the flow of traffic at an intersection with multiple roads.
- The system should support different types of signals, such as red, yellow, and green.
- The duration of each signal should be configurable and adjustable based on traffic conditions.
- The system should handle the transition between signals smoothly, ensuring safe and efficient traffic flow.
- The system should be able to detect and handle emergency situations, such as an ambulance or fire truck approaching the intersection.
- The system should be scalable and extensible to support additional features and functionality.

## Coffee Vending Machine
### Requirements
- The coffee vending machine should support different types of coffee, such as espresso, cappuccino, and latte.
- Each type of coffee should have a specific price and recipe (ingredients and their quantities).
- The machine should have a menu to display the available coffee options and their prices.
- Users should be able to select a coffee type and make a payment.
- The machine should dispense the selected coffee and provide change if necessary.
- The machine should track the inventory of ingredients and notify when they are running low.
- The machine should handle multiple user requests concurrently and ensure thread safety.



## Task Management System
### Requirements
- The task management system should allow users to create, update, and delete tasks.
- Each task should have a title, description, due date, priority, and status (e.g., pending, in progress, completed).
- Users should be able to assign tasks to other users and set reminders for tasks.
- The system should support searching and filtering tasks based on various criteria (e.g., priority, due date, assigned user).
- Users should be able to mark tasks as completed and view their task history.
- The system should handle concurrent access to tasks and ensure data consistency.
- The system should be extensible to accommodate future enhancements and new features.

ATM System
Requirements
The ATM system should support basic operations such as balance inquiry, cash withdrawal, and cash deposit.
Users should be able to authenticate themselves using a card and a PIN (Personal Identification Number).
The system should interact with a bank's backend system to validate user accounts and perform transactions.
The ATM should have a cash dispenser to dispense cash to users.
The system should handle concurrent access and ensure data consistency.
The ATM should have a user-friendly interface for users to interact with.


Professional Networking Platform like LinkedIn
Requirements
User Registration and Authentication:
Users should be able to create an account with their professional information, such as name, email, and password.
Users should be able to log in and log out of their accounts securely.
User Profiles:
Each user should have a profile with their professional information, such as profile picture, headline, summary, experience, education, and skills.
Users should be able to update their profile information.
Connections:
Users should be able to send connection requests to other users.
Users should be able to accept or decline connection requests.
Users should be able to view their list of connections.
Messaging:
Users should be able to send messages to their connections.
Users should be able to view their inbox and sent messages.
Job Postings:
Employers should be able to post job listings with details such as title, description, requirements, and location.
Users should be able to view and apply for job postings.
Search Functionality:
Users should be able to search for other users, companies, and job postings based on relevant criteria.
Search results should be ranked based on relevance and user preferences.
Notifications:
Users should receive notifications for events such as connection requests, messages, and job postings.
Notifications should be delivered in real-time.
Scalability and Performance:
The system should be designed to handle a large number of concurrent users and high traffic load.
The system should be scalable and efficient in terms of resource utilization.

LRU Cache
Requirements
The LRU cache should support the following operations:
put(key, value): Insert a key-value pair into the cache. If the cache is at capacity, remove the least recently used item before inserting the new item.
get(key): Get the value associated with the given key. If the key exists in the cache, move it to the front of the cache (most recently used) and return its value. If the key does not exist, return -1.
The cache should have a fixed capacity, specified during initialization.
The cache should be thread-safe, allowing concurrent access from multiple threads.
The cache should be efficient in terms of time complexity for both put and get operations, ideally O(1).


Tic Tac Toe Game
Requirements
The Tic-Tac-Toe game should be played on a 3x3 grid.
Two players take turns marking their symbols (X or O) on the grid.
The first player to get three of their symbols in a row (horizontally, vertically, or diagonally) wins the game.
If all the cells on the grid are filled and no player has won, the game ends in a draw.
The game should have a user interface to display the grid and allow players to make their moves.
The game should handle player turns and validate moves to ensure they are legal.


Pub-Sub System in Java
Requirements
The Pub-Sub system should allow publishers to publish messages to specific topics.
Subscribers should be able to subscribe to topics of interest and receive messages published to those topics.
The system should support multiple publishers and subscribers.
Messages should be delivered to all subscribers of a topic in real-time.
The system should handle concurrent access and ensure thread safety.
The Pub-Sub system should be scalable and efficient in terms of message delivery.

Elevator System
Requirements
The elevator system should consist of multiple elevators serving multiple floors.
Each elevator should have a capacity limit and should not exceed it.
Users should be able to request an elevator from any floor and select a destination floor.
The elevator system should efficiently handle user requests and optimize the movement of elevators to minimize waiting time.
The system should prioritize requests based on the direction of travel and the proximity of the elevators to the requested floor.
The elevators should be able to handle multiple requests concurrently and process them in an optimal order.
The system should ensure thread safety and prevent race conditions when multiple threads interact with the elevators.


Car Rental System
Requirements
The car rental system should allow customers to browse and reserve available cars for specific dates.
Each car should have details such as make, model, year, license plate number, and rental price per day.
Customers should be able to search for cars based on various criteria, such as car type, price range, and availability.
The system should handle reservations, including creating, modifying, and canceling reservations.
The system should keep track of the availability of cars and update their status accordingly.
The system should handle customer information, including name, contact details, and driver's license information.
The system should handle payment processing for reservations.
The system should be able to handle concurrent reservations and ensure data consistency.


Online Auction System
Requirements
The online auction system should allow users to register and log in to their accounts.
Users should be able to create new auction listings with details such as item name, description, starting price, and auction duration.
Users should be able to browse and search for auction listings based on various criteria (e.g., item name, category, price range).
Users should be able to place bids on active auction listings.
The system should automatically update the current highest bid and notify the bidders accordingly.
The auction should end when the specified duration is reached, and the highest bidder should be declared the winner.
The system should handle concurrent access to auction listings and ensure data consistency.
The system should be extensible to accommodate future enhancements and new features.


Hotel Management System
Requirements
The hotel management system should allow guests to book rooms, check-in, and check-out.
The system should manage different types of rooms, such as single, double, deluxe, and suite.
The system should handle room availability and reservation status.
The system should allow the hotel staff to manage guest information, room assignments, and billing.
The system should support multiple payment methods, such as cash, credit card, and online payment.
The system should handle concurrent bookings and ensure data consistency.
The system should provide reporting and analytics features for hotel management.
The system should be scalable and handle a large number of rooms and guests.


Digital Wallet Service
Requirements
The digital wallet should allow users to create an account and manage their personal information.
Users should be able to add and remove payment methods, such as credit cards or bank accounts.
The digital wallet should support fund transfers between users and to external accounts.
The system should handle transaction history and provide a statement of transactions.
The digital wallet should support multiple currencies and perform currency conversions.
The system should ensure the security of user information and transactions.
The digital wallet should handle concurrent transactions and ensure data consistency.
The system should be scalable to handle a large number of users and transactions.


Airline Management System
Requirements
The airline management system should allow users to search for flights based on source, destination, and date.
Users should be able to book flights, select seats, and make payments.
The system should manage flight schedules, aircraft assignments, and crew assignments.
The system should handle passenger information, including personal details and baggage information.
The system should support different types of users, such as passengers, airline staff, and administrators.
The system should be able to handle cancellations, refunds, and flight changes.
The system should ensure data consistency and handle concurrent access to shared resources.
The system should be scalable and extensible to accommodate future enhancements and new features.

Library Management System
Requirements
The library management system should allow librarians to manage books, members, and borrowing activities.
The system should support adding, updating, and removing books from the library catalog.
Each book should have details such as title, author, ISBN, publication year, and availability status.
The system should allow members to borrow and return books.
Each member should have details such as name, member ID, contact information, and borrowing history.
The system should enforce borrowing rules, such as a maximum number of books that can be borrowed at a time and loan duration.
The system should handle concurrent access to the library catalog and member records.
The system should be extensible to accommodate future enhancements and new features.


Social Network Like Facebook
Requirements
User Registration and Authentication:
Users should be able to create an account with their personal information, such as name, email, and password.
Users should be able to log in and log out of their accounts securely.
User Profiles:
Each user should have a profile with their information, such as profile picture, bio, and interests.
Users should be able to update their profile information.
Friend Connections:
Users should be able to send friend requests to other users.
Users should be able to accept or decline friend requests.
Users should be able to view their list of friends.
Posts and Newsfeed:
Users should be able to create posts with text, images, or videos.
Users should be able to view a newsfeed consisting of posts from their friends and their own posts.
The newsfeed should be sorted in reverse chronological order.
Likes and Comments:
Users should be able to like and comment on posts.
Users should be able to view the list of likes and comments on a post.
Privacy and Security:
Users should be able to control the visibility of their posts and profile information.
The system should enforce secure access control to ensure data privacy.
Notifications:
Users should receive notifications for events such as friend requests, likes, comments, and mentions.
Notifications should be delivered in real-time.
Scalability and Performance:
The system should be designed to handle a large number of concurrent users and high traffic load.
The system should be scalable and efficient in terms of resource utilization.


Restaurant Management System
Requirements
The restaurant management system should allow customers to place orders, view the menu, and make reservations.
The system should manage the restaurant's inventory, including ingredients and menu items.
The system should handle order processing, including order preparation, billing, and payment.
The system should support multiple payment methods, such as cash, credit card, and mobile payments.
The system should manage staff information, including roles, schedules, and performance tracking.
The system should generate reports and analytics for management, such as sales reports and inventory analysis.
The system should handle concurrent access and ensure data consistency.


Concert Ticket Booking System
Requirements
The concert ticket booking system should allow users to view available concerts and their seating arrangements.
Users should be able to search for concerts based on various criteria such as artist, venue, date, and time.
Users should be able to select seats and purchase tickets for a specific concert.
The system should handle concurrent booking requests to avoid double-booking of seats.
The system should ensure fair booking opportunities for all users.
The system should handle payment processing securely.
The system should generate booking confirmations and send them to users via email or SMS.
The system should provide a waiting list functionality for sold-out concerts.


Cricket Information System like CricInfo
Requirements
The Cricinfo system should provide information about cricket matches, teams, players, and live scores.
Users should be able to view the schedule of upcoming matches and the results of completed matches.
The system should allow users to search for specific matches, teams, or players.
Users should be able to view detailed information about a particular match, including the scorecard, commentary, and statistics.
The system should support real-time updates of live scores and match information.
The system should handle concurrent access to match data and ensure data consistency.
The system should be scalable and able to handle a large volume of user requests.
The system should be extensible to accommodate new features and enhancements in the future.

Splitwise
Requirements
The system should allow users to create accounts and manage their profile information.
Users should be able to create groups and add other users to the groups.
Users should be able to add expenses within a group, specifying the amount, description, and participants.
The system should automatically split the expenses among the participants based on their share.
Users should be able to view their individual balances with other users and settle up the balances.
The system should support different split methods, such as equal split, percentage split, and exact amounts.
Users should be able to view their transaction history and group expenses.
The system should handle concurrent transactions and ensure data consistency.


Chess Game
Requirements
The chess game should follow the standard rules of chess.
The game should support two players, each controlling their own set of pieces.
The game board should be represented as an 8x8 grid, with alternating black and white squares.
Each player should have 16 pieces: 1 king, 1 queen, 2 rooks, 2 bishops, 2 knights, and 8 pawns.
The game should validate legal moves for each piece and prevent illegal moves.
The game should detect checkmate and stalemate conditions.
The game should handle player turns and allow players to make moves alternately.
The game should provide a user interface for players to interact with the game.

Snake and Ladder Game
Requirements
The game should be played on a board with numbered cells, typically with 100 cells.
The board should have a predefined set of snakes and ladders, connecting certain cells.
The game should support multiple players, each represented by a unique game piece.
Players should take turns rolling a dice to determine the number of cells to move forward.
If a player lands on a cell with the head of a snake, they should slide down to the cell with the tail of the snake.
If a player lands on a cell with the base of a ladder, they should climb up to the cell at the top of the ladder.
The game should continue until one of the players reaches the final cell on the board.
The game should handle multiple game sessions concurrently, allowing different groups of players to play independently.

Ride-Sharing Service Like Uber
Requirements
The ride sharing service should allow passengers to request rides and drivers to accept and fulfill those ride requests.
Passengers should be able to specify their pickup location, destination, and desired ride type (e.g., regular, premium).
Drivers should be able to see available ride requests and choose to accept or decline them.
The system should match ride requests with available drivers based on proximity and other factors.
The system should calculate the fare for each ride based on distance, time, and ride type.
The system should handle payments and process transactions between passengers and drivers.
The system should provide real-time tracking of ongoing rides and notify passengers and drivers about ride status updates.
The system should handle concurrent requests and ensure data consistency.


University Course Registration System
Requirements
The course registration system should allow students to register for courses and view their registered courses.
Each course should have a course code, name, instructor, and maximum enrollment capacity.
Students should be able to search for courses based on course code or name.
The system should prevent students from registering for courses that have reached their maximum enrollment capacity.
The system should handle concurrent registration requests from multiple students.
The system should ensure data consistency and prevent race conditions.
The system should be extensible to accommodate future enhancements and new features.


Movie Ticket Booking System like BookMyShow
Requirements
The system should allow users to view the list of movies playing in different theaters.
Users should be able to select a movie, theater, and show timing to book tickets.
The system should display the seating arrangement of the selected show and allow users to choose seats.
Users should be able to make payments and confirm their booking.
The system should handle concurrent bookings and ensure seat availability is updated in real-time.
The system should support different types of seats (e.g., normal, premium) and pricing.
The system should allow theater administrators to add, update, and remove movies, shows, and seating arrangements.
The system should be scalable to handle a large number of concurrent users and bookings.


Online Shopping System Like Amazon
Requirements
The online shopping service should allow users to browse products, add them to the shopping cart, and place orders.
The system should support multiple product categories and provide search functionality.
Users should be able to manage their profiles, view order history, and track order status.
The system should handle inventory management and update product availability accordingly.
The system should support multiple payment methods and ensure secure transactions.
The system should handle concurrent user requests and ensure data consistency.
The system should be scalable to handle a large number of products and users.
The system should provide a user-friendly interface for a seamless shopping experience.


Online Stock Brokerage System
Requirements
The online stock brokerage system should allow users to create and manage their trading accounts.
Users should be able to buy and sell stocks, as well as view their portfolio and transaction history.
The system should provide real-time stock quotes and market data to users.
The system should handle order placement, execution, and settlement processes.
The system should enforce various business rules and validations, such as checking account balances and stock availability.
The system should handle concurrent user requests and ensure data consistency and integrity.
The system should be scalable and able to handle a large number of users and transactions.
The system should be secure and protect sensitive user information.


Online Music Streaming Service Like Spotify
Requirements
The music streaming service should allow users to browse and search for songs, albums, and artists.
Users should be able to create and manage playlists.
The system should support user authentication and authorization.
Users should be able to play, pause, skip, and seek within songs.
The system should recommend songs and playlists based on user preferences and listening history.
The system should handle concurrent requests and ensure smooth streaming experience for multiple users.
The system should be scalable and handle a large volume of songs and users.
The system should be extensible to support additional features such as social sharing and offline playback.


Online Food Delivery Service Like Swiggy
Requirements
The food delivery service should allow customers to browse restaurants, view menus, and place orders.
Restaurants should be able to manage their menus, prices, and availability.
Delivery agents should be able to accept and fulfill orders.
The system should handle order tracking and status updates.
The system should support multiple payment methods.
The system should handle concurrent orders and ensure data consistency.
The system should be scalable and handle a high volume of orders.
The system should provide real-time notifications to customers, restaurants, and delivery agents.