CC = g++
CFLAGS = -g -O3 -Wall
LIBS = -lisal
INCLUDES =
OBJS = tester.o drc.o drc633.o drc643.o drc864.o drc953.o drc963.o ia.o butterfly64.o butterfly86.o rsbase.o car.o

all: tester

%.o: %.cc
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

%.o: %.cc %.hh
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

tester: $(OBJS)
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $(OBJS) $(LIBS)

clean:
	rm -f *.o
	rm -f tester

