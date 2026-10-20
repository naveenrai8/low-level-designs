# Parking Lot System - Test Cases

## Test Case 1: Vehicle Entry and Spot Assignment
**Scenario:** A car enters an empty parking lot
- **Given:** Parking lot has available spots for cars
- **When:** Car requests entry
- **Then:** System assigns appropriate spot and generates ticket with entry time

## Test Case 2: Multiple Vehicle Types
**Scenario:** Different vehicle types enter the parking lot
- **Given:** Parking lot has spots for motorcycles, cars, and trucks
- **When:** One of each vehicle type requests entry
- **Then:** Each vehicle gets assigned to appropriate spot type

## Test Case 3: Vehicle Exit and Fee Calculation
**Scenario:** Vehicle exits after parking
- **Given:** Vehicle has been parked for 2 hours
- **When:** Vehicle exits with ticket
- **Then:** System calculates fee based on duration and releases spot

## Test Case 4: No Available Spots
**Scenario:** Parking lot is full
- **Given:** All spots are occupied
- **When:** New vehicle requests entry
- **Then:** System rejects entry with "no spots available" message

## Test Case 5: Concurrent Entry/Exit
**Scenario:** Multiple vehicles enter/exit simultaneously
- **Given:** Parking lot with multiple entry/exit points
- **When:** 10 vehicles try to enter concurrently and 5 exit
- **Then:** All operations complete successfully without data corruption

## Test Case 6: Real-time Availability Tracking
**Scenario:** Check available spots
- **Given:** Parking lot with mixed occupancy
- **When:** System queries availability
- **Then:** Returns accurate count per vehicle type and level

## Test Case 7: Invalid Ticket
**Scenario:** Vehicle tries to exit with invalid ticket
- **Given:** Vehicle at exit
- **When:** Provides non-existent ticket ID
- **Then:** System rejects exit with error

## Test Case 8: Multi-level Parking
**Scenario:** Spots filled across multiple levels
- **Given:** 3-level parking lot, level 1 is full
- **When:** New vehicle requests entry
- **Then:** System assigns spot from level 2 or 3

## Test Case 9: Spot Type Mismatch
**Scenario:** Truck tries to park in car spot
- **Given:** Only car spots available
- **When:** Truck requests entry
- **Then:** System rejects or handles appropriately based on business logic

## Test Case 10: Long Duration Parking
**Scenario:** Vehicle parked for extended period
- **Given:** Vehicle parked for 48 hours
- **When:** Vehicle exits
- **Then:** System calculates correct fee with potential daily caps
